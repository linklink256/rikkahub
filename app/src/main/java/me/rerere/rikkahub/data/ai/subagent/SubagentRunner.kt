package me.rerere.rikkahub.data.ai.subagent

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
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
    ): Flow<SubagentEvent> = flow {
        val model = resolveModel(definition, settings, parentModel)
        val providerSetting = model.findProvider(settings.providers)
            ?: error("Provider not found for model ${model.modelId}")
        val providerImpl = providerManager.getProviderByType(providerSetting)

        // 工具白名单：定义未声明 tools 时继承全部工具池
        val allowedTools = toolPool.values.filter {
            definition.tools.isEmpty() || it.name in definition.tools
        }

        // 预填充：默认关闭（避免抢占产出物）；raw 模式强制关闭；
        // 仅显式开启且非 Google provider 时注入。Google Gemini API 不允许以 assistant 消息结尾，自动禁用
        val usePrefill = prefill && !contract.raw && providerImpl !is GoogleProvider
        val contract = SubagentResultContract.forRole(definition.resultFormat)
        val prefillText = if (usePrefill) contract.prefill else null

        val systemPrompt = buildSystemPrompt(definition, envelope, model, allowedTools, contract)
        var messages = buildList {
            add(UIMessage.system(systemPrompt))
            add(UIMessage.user(buildUserMessage(envelope)))
            if (prefillText != null) {
                add(UIMessage.assistant(prefillText))
            }
        }

        emit(SubagentEvent.Started(agentId, definition.name, envelope.task))

        try {
            withTimeout(timeoutMillis) {
                // 无步数限制：直到模型不再调用工具（任务完成）或超时
                var step = 0
                while (true) {
                    step++
                    emit(SubagentEvent.StepStarted(agentId, step))
                    Log.i(TAG, "run: step $step ($agentId)")

                    val params = TextGenerationParams(
                        model = model,
                        tools = allowedTools,
                        reasoningLevel = definition.reasoningLevel ?: ReasoningLevel.OFF,
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

                    // 执行工具（子 agent 不弹审批，直接执行或按需跳过）
                    val executedTools = toolsToProcess.map { tool ->
                        emit(SubagentEvent.ToolCall(agentId, tool.toolName, tool.input))
                        val output = executeTool(tool, allowedTools)
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
            }

            emit(SubagentEvent.Finished(agentId, parseResult(messages, prefillText, contract)))
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
        }
    }

    // ---- 内部实现 ----

    /**
     * 解析子代理配置的模型。
     *
     * `model` 字段支持两种格式：
     * - `model: gpt-4o`              只按模型 ID/显示名匹配（同名时命中第一个配置的供应商）
     * - `model: OpenAI/gpt-4o`       或 `openai:gpt-4o`，精确指定供应商
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
                "（支持 '供应商/模型ID' 格式，如 OpenAI/gpt-4o），或在设置中添加该模型；" +
                "清空 model 字段则继承主 agent 模型。"
        )
    }

    private fun buildSystemPrompt(
        definition: SubagentMetadata,
        envelope: TaskEnvelope,
        model: Model,
        tools: List<Tool>,
        contract: RoleContract,
    ): String = buildString {
        // 角色定义正文
        val body = runCatching {
            SkillFrontmatterParser.extractBody(definition.agentFile.readText())
        }.getOrDefault("")
        if (body.isNotBlank()) {
            appendLine(body.trim())
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
    }.trim()

    private fun buildUserMessage(envelope: TaskEnvelope): String = buildString {
        appendLine("请以 ${envelope.role} 角色完成任务。")
        appendLine()
        appendLine(envelope.task)
    }.trim()

    private suspend fun executeTool(
        tool: UIMessagePart.Tool,
        allowedTools: List<Tool>,
    ): List<UIMessagePart> {
        val toolDef = allowedTools.find { it.name == tool.toolName }
            ?: return errorOutput("Tool ${tool.toolName} is not in the allowed tool list")

        val args: JsonElement = runCatching {
            JsonInstant.parseToJsonElement(tool.input.ifBlank { "{}" })
        }.getOrElse {
            return errorOutput("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}")
        }

        if (toolDef.needsApproval(args)) {
            return errorOutput("Tool ${tool.toolName} requires user approval, skipped in subagent")
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
        val parsed = StructuredResultParser.parse(text, prefill, contract.sections, rawMode = contract.raw)
        return parsed.copy(
            usage = lastAssistant?.usage ?: parsed.usage,
        )
    }
}
