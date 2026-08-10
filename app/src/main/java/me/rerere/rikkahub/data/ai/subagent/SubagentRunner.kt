package me.rerere.rikkahub.data.ai.subagent

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.GoogleProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SubagentMetadata
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

/**
 * 子代理（Subagent）运行器。
 *
 * 在独立会话中运行一个子 agent：独立的 system prompt（角色 body + 任务包）、
 * 独立的工具白名单、可选的独立模型。通过 [Flow] 流式上报 [SubagentEvent]，
 * 供 UI 实时展示与父会话感知。
 *
 * 每个子 agent 不继承父会话历史，只接收 [TaskEnvelope] 这个最小上下文包。
 */
class SubagentRunner(
    private val providerManager: ProviderManager,
) {
    companion object {
        private const val TAG = "SubagentRunner"
        private const val DEFAULT_TIMEOUT_MILLIS = 10 * 60 * 1000L

        /** 默认最大工具调用轮数：防止模型空耗循环（反复调工具无进展） */
        private const val DEFAULT_MAX_STEPS = 30

        /** 默认单步工具执行超时（毫秒）：防止某个工具（网络请求等）卡死拖住整轮 */
        private const val DEFAULT_STEP_TIMEOUT_MILLIS = 120_000L

        /** 连续相同工具调用（工具名+参数完全一致）达到该次数 → 判定死循环，提前终止 */
        private const val REPEATED_CALL_LIMIT = 5

        /** 空结果自动重试的最大尝试次数（首次 + 重试一次） */
        private const val MAX_EMPTY_RETRY_ATTEMPTS = 2

        /** 空结果重试提示：明示上次未产出内容，要求先落盘再返回 */
        private const val EMPTY_RESULT_RETRY_HINT =
            "你的上一次运行未产出任何内容（没有输出文本，也没有落盘任何文件）。" +
                "请立即直接完成当前任务：先写好完整产出物并落盘，再给出简短总结。" +
                "注意：一次成稿，不要自我迭代，不要空手返回。"

        /** 上下文压缩阈值（字符）：超过后自动把历史压缩成摘要（Deep Agents 风格） */
        private const val DEFAULT_CONTEXT_LIMIT = 90_000

        /** 记忆文件单次追加的最大字符数 */
        private const val MEMORY_APPEND_LIMIT = 2_000

        /** 记忆文件总大小上限（字符），防止无限膨胀 */
        private const val MEMORY_FILE_LIMIT = 8_000
    }

    /**
     * @param definition   角色定义（含 tools 白名单 / model / reasoningLevel）
     * @param envelope     任务包（父 agent 委派的最小上下文）
     * @param settings     全局设置（用于定位 provider）
     * @param parentModel  主 agent 当前模型（角色未指定模型时继承其 provider）
     * @param toolPool     可用工具池（父会话提供的全部工具，按白名单过滤）
     * @param timeoutMillis 总超时
     * @param prefill      是否注入 assistant 预填充消息。默认关闭：prefill 会强制模型从结果块开头续写，
     *                     抢占完整产出物（writer 等产出型角色会被压缩成摘要）。纯摘要型角色（如调研）
     *                     可显式开启以保证格式稳定；Google provider 自动禁用。
     * @param retryOnEmpty 空结果自动重试：首次运行未产出任何内容（无文本、无落盘）时，
     *                     自动追加提示重跑一次，避免流水线白等一轮（flash 模型偶发"只思考不落盘"）。
     * @param memoryFile   会话间记忆文件路径（如 /workspace/.cache/subagent-memory/<role>.md）。
     *                     非空且工作区工具可用时：任务开始前自动读取记忆注入 system prompt，
     *                     任务结束后自动把结果摘要追加写入（Deep Agents 持久记忆风格）。
     * @param contextLimit 上下文压缩阈值（字符）。历史超过该值时自动调用模型压缩中间消息为摘要，
     *                     避免长任务上下文爆炸（Deep Agents 风格）；0 = 关闭。
     * @param memoryLock   会话间记忆锁：并行任务共享同一 Mutex 时，记忆文件的"读-改-写"会被串行化，
     *                     避免并发写覆盖导致记忆丢失（ISS-002）；null = 不加锁。
     * @param maxSteps     最大工具调用轮数（空耗防护）。达到上限立即停止并返回已有产出；
     *                     默认 [DEFAULT_MAX_STEPS]，角色 AGENT.md 的 `maxSteps` 可覆盖。
     * @param stepTimeoutMillis 单步工具执行超时（毫秒）。某个工具调用超时按失败处理并继续；
     *                     默认 [DEFAULT_STEP_TIMEOUT_MILLIS]，角色 AGENT.md 的 `stepTimeout`（秒）可覆盖。
     */
    fun run(
        agentId: String = Uuid.random().toString(),
        definition: SubagentMetadata,
        envelope: TaskEnvelope,
        settings: Settings,
        parentModel: Model,
        toolPool: Map<String, Tool>,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        prefill: Boolean = false,
        retryOnEmpty: Boolean = true,
        memoryFile: String? = null,
        contextLimit: Int = DEFAULT_CONTEXT_LIMIT,
        memoryLock: Mutex? = null,
        maxSteps: Int = definition.maxSteps ?: DEFAULT_MAX_STEPS,
        stepTimeoutMillis: Long = definition.stepTimeoutMillis ?: DEFAULT_STEP_TIMEOUT_MILLIS,
    ): Flow<SubagentEvent> = flow {
        val model = resolveModel(definition, settings, parentModel)
        val providerSetting = model.findProvider(settings.providers)
            ?: error("Provider not found for model ${model.modelId}")
        val providerImpl = providerManager.getProviderByType(providerSetting)

        // 工具白名单三态：
        // - toolsDisabled（`tools: none`）→ 禁用全部工具，子代理无任何工具可用
        // - tools 为空（未声明）→ 继承全部工具池
        // - tools 非空 → 只保留白名单内的工具
        val allowedTools = when {
            definition.toolsDisabled -> emptyList()
            definition.tools.isEmpty() -> toolPool.values.toList()
            else -> toolPool.values.filter { it.name in definition.tools }
        }

        // 工具预检（Claude Code 风格）：AGENT.md 声明的工具必须能在工具池中解析，
        // 解析不到立即失败并给出诊断，避免模型白跑一轮后才在工具调用时报错。
        // `tools: none`（禁用全部）不参与预检。
        if (definition.tools.isNotEmpty() && !definition.toolsDisabled) {
            val unresolved = definition.tools.filter { it !in toolPool }
            if (unresolved.isNotEmpty()) {
                emit(
                    SubagentEvent.Failed(
                        agentId,
                        buildString {
                            appendLine("AGENT.md tools 无法解析: ${unresolved.joinToString(", ")}")
                            appendLine("可用工具: ${toolPool.keys.joinToString(", ").ifEmpty { "(none)" }}")
                            appendLine("Hint: workspace_* 工具仅在父会话绑定 workspace 且 shell READY 时可用；请检查工作区设置，或修正 AGENT.md 的 tools 列表。")
                        }
                    )
                )
                return@flow
            }
        }

        // 会话间记忆：任务开始前自动读取记忆文件（工作区工具可用时）
        val memoryText = if (memoryFile != null) readMemoryFile(toolPool, memoryFile) else null

        // 预填充：默认关闭（避免抢占产出物）；raw 模式强制关闭；
        // 仅显式开启且非 Google provider 时注入。Google Gemini API 不允许以 assistant 消息结尾，自动禁用
        val contract = SubagentResultContract.forRole(definition.resultFormat)
        val usePrefill = prefill && !contract.raw && providerImpl !is GoogleProvider
        val prefillText = if (usePrefill) contract.prefill else null

        val systemPrompt = buildSystemPrompt(definition, envelope, model, allowedTools, contract, memoryText)
        var messages = buildList {
            add(UIMessage.system(systemPrompt))
            add(UIMessage.user(buildUserMessage(envelope)))
            if (prefillText != null) {
                add(UIMessage.assistant(prefillText))
            }
        }

        emit(SubagentEvent.Started(agentId, definition.name, envelope.task))

        // 空结果自动重试：最多 2 次尝试（首次 + 重试一次）
        val maxAttempts = if (retryOnEmpty) MAX_EMPTY_RETRY_ATTEMPTS else 1
        var attempt = 1
        while (true) {
            val result = try {
                // 空耗防护触发原因：在 withTimeout 内置位，lambda 外读取（lambda 内不能 return@flow）
                var guardStopReason: String? = null
                withTimeout(timeoutMillis) {
                    // 无步数限制：直到模型不再调用工具（任务完成）或超时
                    var step = 0
                    // 重复调用检测：记录最近一次工具调用签名（toolName+input），连续相同达到上限判定死循环
                    var lastCallSignature: String? = null
                    var repeatedCallCount = 0
                    while (true) {
                        step++
                        emit(SubagentEvent.StepStarted(agentId, step))
                        Log.i(TAG, "run: step $step ($agentId)")

                        // 空耗防护 1：最大步数上限。达到上限立即停止，保留已有产出。
                        if (step > maxSteps) {
                            Log.w(TAG, "run: max steps ($maxSteps) reached at step $step ($agentId)")
                            guardStopReason = "reached max steps $maxSteps"
                            break
                        }

                        // 上下文管理：历史超阈值时压缩中间消息为摘要（Deep Agents 风格）
                        if (contextLimit > 0 && messages.textChars() > contextLimit) {
                            messages = compressHistory(providerImpl, providerSetting, model, messages)
                            Log.w(TAG, "run: context compressed at step $step ($agentId)")
                        }

                        val params = TextGenerationParams(
                            model = model,
                            tools = allowedTools,
                            reasoningLevel = definition.reasoningLevel ?: ReasoningLevel.OFF,
                            // json 模式：OpenAI 系注入 response_format 强制 JSON 输出（Claude/Google 靠 prompt 引导）
                            customBody = if (contract.json && providerSetting is ProviderSetting.OpenAI) {
                                listOf(
                                    CustomBody(
                                        key = "response_format",
                                        value = JsonInstant.parseToJsonElement("""{"type":"json_object"}"""),
                                    )
                                )
                            } else {
                                emptyList()
                            },
                        )

                        // 流式收集模型输出，边收边上报进度
                        providerImpl.streamText(providerSetting, messages, params).collect { chunk ->
                            messages = messages.handleMessageChunk(chunk, model)
                            chunk.choices.firstOrNull()?.delta?.parts.orEmpty()
                                .filterIsInstance<UIMessagePart.Text>()
                                .forEach { part ->
                                    if (part.text.isNotBlank()) {
                                        emit(SubagentEvent.Progress(agentId, part.text))
                                    }
                                }
                        }

                        val lastMessage = messages.lastOrNull() ?: break
                        val toolsToProcess = lastMessage.parts
                            .filterIsInstance<UIMessagePart.Tool>()
                            .filter { !it.isExecuted }
                        if (toolsToProcess.isEmpty()) break

                        // 空耗防护 2：重复工具调用检测（相同工具+相同参数连续出现 → 死循环）
                        val signature = toolsToProcess.joinToString("|") { "${it.toolName}(${it.input.take(200)})" }
                        if (signature == lastCallSignature) {
                            repeatedCallCount++
                            if (repeatedCallCount >= REPEATED_CALL_LIMIT) {
                                Log.w(TAG, "run: repeated tool calls detected ($repeatedCallCount x same call) at step $step ($agentId)")
                                guardStopReason = "stopped: repeated identical tool call $repeatedCallCount times"
                                break
                            }
                        } else {
                            lastCallSignature = signature
                            repeatedCallCount = 1
                        }

                        // 执行工具（子 agent 不弹审批，直接执行或按需跳过）
                        // 空耗防护 3：单步工具超时，卡住的工具按失败处理并继续
                        val executedTools = toolsToProcess.map { tool ->
                            emit(SubagentEvent.ToolCall(agentId, tool.toolName, tool.input))
                            val output = runCatching {
                                withTimeout(stepTimeoutMillis) {
                                    executeTool(tool, allowedTools, definition)
                                }
                            }.getOrElse { e ->
                                if (e is CancellationException) throw e
                                Log.w(TAG, "run: tool ${tool.toolName} timed out after ${stepTimeoutMillis}ms ($agentId)")
                                errorOutput("Tool '${tool.toolName}' timed out after ${stepTimeoutMillis / 1000}s")
                            }
                            val summary = output
                                .filterIsInstance<UIMessagePart.Text>()
                                .joinToString(" ") { it.text }
                                .take(200)
                            emit(SubagentEvent.ToolResult(agentId, tool.toolName, summary))
                            tool.copy(output = output)
                        }

                        // 将工具输出回填到最后一条消息，继续下一轮
                        val updatedParts = lastMessage.parts.map { part ->
                            if (part is UIMessagePart.Tool) {
                                executedTools.find { it.toolCallId == part.toolCallId } ?: part
                            } else {
                                part
                            }
                        }
                        messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    }
                    // 空耗防护触发原因传给 withTimeout 外层（lambda 内不能 return@flow）
                    guardStopReason
                }
                // 空耗防护触发：停止后解析已有产出并返回（带停止原因）
                if (guardStopReason != null) {
                    val partial = parseResult(messages, prefillText, contract)
                    emit(
                        SubagentEvent.Finished(
                            agentId = agentId,
                            result = partial.copy(
                                summary = buildString {
                                    if (partial.summary.isNotBlank()) {
                                        append(partial.summary)
                                        append(' ')
                                    }
                                    append("($guardStopReason)")
                                },
                            ),
                        )
                    )
                    return@flow
                }
                parseResult(messages, prefillText, contract)
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "run: timeout ($agentId)")
                emit(
                    SubagentEvent.Finished(
                        agentId = agentId,
                        result = AgentResult(
                            status = AgentResultStatus.TIMEOUT,
                            summary = "Subagent timed out after ${timeoutMillis / 1000}s",
                        ),
                    )
                )
                return@flow
            } catch (e: CancellationException) {
                emit(
                    SubagentEvent.Finished(
                        agentId = agentId,
                        result = AgentResult(status = AgentResultStatus.CANCELLED, summary = "Cancelled"),
                    )
                )
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "run: failed ($agentId)", e)
                emit(SubagentEvent.Failed(agentId, e.message ?: e.javaClass.simpleName))
                return@flow
            }

            // 正常完成：非空结果或已达重试上限 → 结束；空结果 → 追加提示重跑
            if (attempt >= maxAttempts || !result.isEmptyResult) {
                // 会话间记忆：任务结束后自动把结果摘要追加写入记忆文件。
                // 锁内"重读最新 + 追加"，避免并行任务互相覆盖（ISS-002）
                if (memoryFile != null && !result.isEmptyResult) {
                    if (memoryLock != null) {
                        memoryLock.withLock {
                            val latest = readMemoryFile(toolPool, memoryFile) ?: memoryText
                            writeMemoryFile(toolPool, memoryFile, latest, result)
                        }
                    } else {
                        writeMemoryFile(toolPool, memoryFile, memoryText, result)
                    }
                }
                emit(SubagentEvent.Finished(agentId, result))
                return@flow
            }
            Log.w(TAG, "run: empty result on attempt $attempt, retrying ($agentId)")
            attempt++
            messages = messages + UIMessage.user(EMPTY_RESULT_RETRY_HINT)
        }
    }

    // ---- 内部实现 ----

    /**
     * 解析子代理配置的模型。
     *
     * `model` 字段支持多种格式：
     * - `model: gpt-4o`              只按模型 ID/显示名匹配（同名时命中第一个配置的供应商）
     * - `model: OpenAI/gpt-4o`       或 `openai:gpt-4o`，精确指定供应商（斜杠或冒号分隔均可）
     * - 留空则继承主 agent 模型
     */
    private fun resolveModel(
        definition: SubagentMetadata,
        settings: Settings,
        parentModel: Model,
    ): Model {
        val modelName = definition.model?.trim().orEmpty()
        if (modelName.isEmpty()) return parentModel

        // 拆分可选的 "供应商/" 或 "供应商:" 前缀
        val slashIndex = modelName.indexOf('/')
        val colonIndex = modelName.indexOf(':')
        val sepIndex = listOf(slashIndex, colonIndex).filter { it >= 0 }.minOrNull()
        val providerFilter = if (sepIndex != null) modelName.substring(0, sepIndex).trim() else null
        val modelPart = if (sepIndex != null) modelName.substring(sepIndex + 1).trim() else modelName

        settings.providers.forEach { provider ->
            if (providerFilter != null && !provider.name.equals(providerFilter, ignoreCase = true)) {
                return@forEach
            }
            provider.models.forEach { model ->
                if (model.modelId == modelPart || model.displayName == modelPart) {
                    return model
                }
            }
        }
        // 配置的模型不存在时明确报错，而不是静默回退到主模型，避免用户误以为配置已生效
        error(
            "Subagent '${definition.name}' 指定的模型 '$modelName' 未配置。请检查 AGENT.md 的 model 字段" +
                "（支持 '供应商/模型ID' 或 '供应商:模型ID' 格式，如 OpenAI/gpt-4o、openai:gpt-4o），" +
                "或在设置中添加该模型；清空 model 字段则继承主 agent 模型。"
        )
    }

    private fun buildSystemPrompt(
        definition: SubagentMetadata,
        envelope: TaskEnvelope,
        model: Model,
        tools: List<Tool>,
        contract: RoleContract,
        memory: String? = null,
    ): String = buildString {
        // 角色定义正文
        val body = runCatching {
            SkillFrontmatterParser.extractBody(definition.agentFile.readText())
        }.getOrDefault("")
        if (body.isNotBlank()) {
            appendLine(body.trim())
            appendLine()
        }

        // 会话间记忆（历史任务留下的持久化记忆，只读参考；任务结束后 Runner 会自动更新）
        if (!memory.isNullOrBlank()) {
            appendLine("## Session Memory")
            appendLine("以下为历史会话留下的记忆，仅作背景参考（可能过期或不完整）：")
            appendLine("- 涉及时效性数据（文件内容、数值、状态）时，必须读取实际文件确认，禁止仅凭记忆作答。")
            appendLine(memory.trim())
            appendLine()
        }

        // 任务包
        appendLine("## Task Envelope")
        appendLine("- Role: ${definition.name}")
        appendLine("- Task: ${envelope.task}")
        if (envelope.boundary.isNotBlank()) appendLine("- Boundary: ${envelope.boundary}")
        if (envelope.context.isNotBlank()) {
            appendLine("- Context:")
            appendLine(envelope.context.trim().prependIndent("  "))
        }
        if (envelope.acceptance.isNotBlank()) appendLine("- Acceptance: ${envelope.acceptance}")
        appendLine()

        // 工具说明
        if (tools.isNotEmpty()) {
            appendLine("## Available Tools")
            tools.forEach { tool ->
                appendLine()
                append(tool.systemPrompt(model, emptyList()))
            }
        }

        // 输出契约：要求最终以结构化格式结束（支持角色自定义段落；raw 模式为空，不注入）
        if (contract.systemPrompt.isNotBlank()) {
            appendLine()
            append(contract.systemPrompt)
        }

        // 通用效率指南：一次成稿、只交付结果、先落盘再返回、减少工具往返
        appendLine()
        append(SubagentResultContract.EFFICIENCY_GUIDELINES)
    }.trim()

    private fun buildUserMessage(envelope: TaskEnvelope): String = buildString {
        appendLine("请以 ${envelope.role} 角色完成任务。")
        appendLine()
        appendLine(envelope.task)
    }.trim()

    private suspend fun executeTool(
        tool: UIMessagePart.Tool,
        allowedTools: List<Tool>,
        definition: SubagentMetadata,
    ): List<UIMessagePart> {
        val toolDef = allowedTools.find { it.name == tool.toolName }
            ?: return errorOutput(buildString {
                appendLine("Tool '${tool.toolName}' is not available in the subagent tool pool.")
                appendLine(
                    "Declared in AGENT.md tools: " + when {
                        definition.toolsDisabled -> "(none - all tools disabled)"
                        definition.tools.isEmpty() -> "(all inherited from parent session)"
                        else -> definition.tools.joinToString(", ")
                    }
                )
                appendLine("Available tools: ${allowedTools.joinToString { it.name }.ifEmpty { "(none)" }}")
                appendLine("Hint: workspace_* tools exist only when the assistant is bound to a workspace whose shell is READY. Check the parent session's Workspace settings; or declare only tools that exist in the parent session.")
            })

        val args: JsonElement = runCatching {
            JsonInstant.parseToJsonElement(tool.input.ifBlank { "{}" })
        }.getOrElse {
            return errorOutput("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}")
        }

        if (toolDef.needsApproval(args)) {
            return errorOutput(buildString {
                appendLine("Tool '${tool.toolName}' requires user approval and was skipped in subagent (subagents have no approval UI).")
                appendLine("Hint: workspace_* approval switches are configured per workspace. To let subagents use this tool, " +
                    "turn off its approval switch in the workspace settings (e.g. workspace_shell approval).")
            })
        }

        return runCatching {
            toolDef.execute(args)
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            errorOutput("[${e.javaClass.name}] ${e.message}")
        }
    }

    private fun errorOutput(message: String): List<UIMessagePart> = listOf(
        UIMessagePart.Text(
            JsonInstant.encodeToString(
                buildJsonObject {
                    put("error", JsonPrimitive(message))
                }
            )
        )
    )

    /**
     * 解析子代理最终输出为结构化 [AgentResult]。
     *
     * 优先使用 [StructuredResultParser] 按角色输出契约解析（Markdown 段落 / JSON），
     * raw 模式（`resultFormat: raw`）跳过解析、内容原样返回；
     * 解析失败时回退为全文摘要；同时回填 token usage 与原始输出。
     */
    private fun parseResult(messages: List<UIMessage>, prefill: String?, contract: RoleContract): AgentResult {
        val lastAssistant = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
        val text = lastAssistant?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("\n") { it.text }
            ?.trim()
            .orEmpty()
        val parsed = StructuredResultParser.parse(
            raw = text,
            prefill = prefill,
            sections = contract.sections,
            rawMode = contract.raw,
            jsonMode = contract.json,
        )
        return parsed.copy(
            usage = lastAssistant?.usage ?: parsed.usage,
        )
    }

    // ---- 会话间记忆（Deep Agents 持久记忆风格）----

    /** 任务开始前：通过工作区工具读取记忆文件内容（静默失败，无工具/文件不存在时返回 null） */
    private suspend fun readMemoryFile(toolPool: Map<String, Tool>, path: String): String? {
        val readTool = toolPool["workspace_read_file"] ?: return null
        val args = runCatching {
            JsonInstant.parseToJsonElement(
                JsonInstant.encodeToString(buildJsonObject { put("path", path) })
            )
        }.getOrNull() ?: return null
        val parts = runCatching { readTool.execute(args) }.getOrNull() ?: return null
        val text = parts.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text ?: return null
        val obj = runCatching { JsonInstant.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        return obj["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    /** 任务结束后：把结果摘要追加写入记忆文件（静默失败）。
     *  优先使用 workspace_shell 原子追加（O_APPEND，并行同角色任务不互相覆盖，ISS-002）；
     *  追加不可用或文件超限时回退 workspace_write_file 覆盖写。 */
    private suspend fun writeMemoryFile(
        toolPool: Map<String, Tool>,
        path: String,
        existing: String?,
        result: AgentResult,
    ) {
        val summary = result.toText.take(MEMORY_APPEND_LIMIT)
        if (summary.isBlank()) return
        val header = "--- ${java.time.Instant.now()} ---"
        val entry = buildString {
            appendLine(header)
            appendLine(summary)
        }
        val appendMode = existing.isNullOrBlank() || existing.length + entry.length <= MEMORY_FILE_LIMIT

        // 方案一：shell 原子追加（heredoc + O_APPEND，并行安全）
        if (appendMode) {
            val shellTool = toolPool["workspace_shell"]
            if (shellTool != null) {
                val delimiter = "RH_EOF_" + Uuid.random().toString().replace("-", "")
                val safeEntry = entry.replace(delimiter, "${delimiter}_x")
                val command = buildString {
                    append("mkdir -p \"\$(dirname \"$path\")\" && cat >> \"$path\" << '$delimiter'\n")
                    append(safeEntry)
                    append('\n')
                    append(delimiter)
                }
                val args = runCatching {
                    JsonInstant.parseToJsonElement(
                        JsonInstant.encodeToString(
                            buildJsonObject {
                                put("command", command)
                                put("timeout", JsonPrimitive(30))
                            }
                        )
                    )
                }.getOrNull()
                if (args != null && runCatching { shellTool.execute(args) }.isSuccess) {
                    return
                }
            }
        }

        // 方案二：覆盖写（追加不可用 / 文件接近上限时压缩保留尾部）
        val writeTool = toolPool["workspace_write_file"] ?: return
        val updated = if (appendMode) {
            (existing.orEmpty() + "\n\n" + entry).take(MEMORY_FILE_LIMIT)
        } else {
            (existing.orEmpty().takeLast(MEMORY_FILE_LIMIT / 2) + "\n\n" + entry).take(MEMORY_FILE_LIMIT)
        }
        val args = runCatching {
            JsonInstant.parseToJsonElement(
                JsonInstant.encodeToString(
                    buildJsonObject {
                        put("path", path)
                        put("text", updated)
                        put("overwrite", JsonPrimitive(true))
                    }
                )
            )
        }.getOrNull() ?: return
        runCatching { writeTool.execute(args) }
    }

    // ---- 上下文管理（Deep Agents 风格：超长自动压缩）----

    /** 消息文本总字符数（仅统计 Text part） */
    private fun List<UIMessage>.textChars(): Int =
        sumOf { msg -> msg.parts.filterIsInstance<UIMessagePart.Text>().sumOf { it.text.length } }

    /**
     * 把中间历史压缩成一条摘要：保留 system + 末尾 keepTail 条消息，
     * 中间部分调用模型生成要点摘要（保留文件路径/决策/数字等硬信息）。
     * 摘要失败时降级为占位提示，保证上下文可继续。
     */
    private suspend fun compressHistory(
        providerImpl: Provider<ProviderSetting>,
        providerSetting: ProviderSetting,
        model: Model,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val keepTail = 2
        if (messages.size <= keepTail + 1) return messages
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        val head = if (systemIndex >= 0) messages.subList(0, systemIndex + 1) else emptyList()
        val tail = messages.takeLast(keepTail)
        val middle = messages.subList(head.size, messages.size - keepTail)
        val middleText = middle.joinToString("\n\n") { msg ->
            msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        }
        val summary = runCatching {
            val chunk = providerImpl.generateText(
                providerSetting,
                listOf(
                    UIMessage.user(
                        "Summarize this agent working conversation into concise bullet points. " +
                            "Keep ALL concrete facts: file paths, decisions, numbers, tool results, remaining todo. " +
                            "Output only the summary:\n\n$middleText"
                    )
                ),
                TextGenerationParams(model = model, maxTokens = 512),
            )
            chunk.choices.firstOrNull()?.message?.parts
                ?.filterIsInstance<UIMessagePart.Text>()
                ?.joinToString("\n") { it.text }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "(history compressed by runner: ${middle.size} earlier messages)"

        Log.w(TAG, "compressHistory: ${messages.size} messages -> ${head.size + 1} + tail ${tail.size}")
        return head + UIMessage.user("## Compressed History\n$summary") + tail
    }
}
