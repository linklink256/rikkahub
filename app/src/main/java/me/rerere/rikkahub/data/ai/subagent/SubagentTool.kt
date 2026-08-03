package me.rerere.rikkahub.data.ai.subagent

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.SubagentManager
import me.rerere.rikkahub.data.files.SubagentMetadata
import me.rerere.rikkahub.utils.JsonInstant

private const val PARALLEL_CONCURRENCY = 4
private const val MAX_SUBTASKS = 8

/**
 * 构建 `subagent` 工具，供主 agent 委派任务给子代理。
 *
 * 支持三种模式：
 * - single：单任务
 * - parallel：有界扇出（上限 8 个子任务，并发 4）
 * - chain：顺序 handoff，上一步结果注入下一步
 *
 * @param toolPoolProvider 延迟获取当前会话的完整工具池（子代理按白名单过滤）
 */
fun createSubagentTool(
    subagentManager: SubagentManager,
    subagentRunner: SubagentRunner,
    settingsStore: SettingsStore,
    toolPoolProvider: () -> List<Tool>,
    model: Model,
): Tool {
    // 仅用于工具描述中的提示信息；execute 内始终动态读取最新角色列表
    val availableRoles = runCatching { subagentManager.listSubagents() }.getOrDefault(emptyList())

    return Tool(
        name = "subagent",
        description = """
            Delegate a focused task to a subagent. Subagents run in an isolated session with their own
            system prompt, tool whitelist and optional model; they do NOT inherit the conversation history.
            Use for large, focused or parallelizable work (research, planning, review, refactoring) to keep
            the main context clean. Returns a structured result.

            WHEN TO USE (be proactive):
            - The user's request matches a role listed in the system prompt (see <available_subagents>).
            - The task is large, independent or parallelizable (research, planning, code review, refactoring,
              multi-file analysis, batch operations). Delegate instead of doing it all in the main context.
            - You need a different model or a focused tool whitelist for a subtask.
            - The task would bloat the conversation history with long file dumps or repetitive steps.

            HOW TO DELEGATE:
            - Pick the best matching role; if unsure, pick the closest one and describe the task precisely.
            - Provide a self-contained `task`; include only a minimal `context` summary, never the full history.
            - For independent subtasks use mode=parallel (max 8 subtasks); for sequential handoffs use mode=chain.
            - Set `boundary` and `acceptance` to constrain the subagent and verify its result.

            Available subagents: ${availableRoles.joinToString { "${it.name}(${it.description})" }}
            Install more roles in Extensions > Subagents.
        """.trimIndent(),
        systemPrompt = { _, _ ->
            // 动态读取当前角色列表注入系统提示（与 skills 的 <available_skills> 注入模式一致），
            // 让主 agent 在每轮对话都能看到可用角色及其用途，从而积极委派。
            val roles = runCatching { subagentManager.listSubagents() }.getOrDefault(emptyList())
            if (roles.isEmpty()) {
                ""
            } else {
                buildString {
                    appendLine("**Subagents**")
                    appendLine(
                        "You can delegate focused tasks to subagents via the `subagent` tool. " +
                            "Be proactive: when the user's request matches one of the roles below, or the task is " +
                            "large/independent/parallelizable, delegate it instead of doing everything yourself."
                    )
                    appendLine("<available_subagents>")
                    roles.groupBy { it.group }.forEach { (group, members) ->
                        appendLine("  <group name=\"$group\">")
                        members.forEach { s ->
                            appendLine("    <subagent>")
                            appendLine("      <name>${s.name}</name>")
                            appendLine("      <description>${s.description}</description>")
                            if (s.tools.isNotEmpty()) {
                                appendLine("      <tools>${s.tools.joinToString(", ")}</tools>")
                            }
                            s.model?.let { appendLine("      <model>$it</model>") }
                            appendLine("    </subagent>")
                        }
                        appendLine("  </group>")
                    }
                    append("</available_subagents>")
                }
            }
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("role", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Subagent role to delegate to. Choose the role whose description best matches the task. " +
                                "Available: ${availableRoles.joinToString { "${it.name} - ${it.description}" }}"
                        )
                    })
                    put("task", buildJsonObject {
                        put("type", "string")
                        put("description", "The concrete task to delegate")
                    })
                    put("boundary", buildJsonObject {
                        put("type", "string")
                        put("description", "Constraints: what the subagent must NOT do (e.g. read-only, only modify specific files)")
                    })
                    put("context", buildJsonObject {
                        put("type", "string")
                        put("description", "Minimal context summary extracted from the conversation (do NOT dump the whole history)")
                    })
                    put("acceptance", buildJsonObject {
                        put("type", "string")
                        put("description", "Acceptance criteria the result must meet")
                    })
                    put("mode", buildJsonObject {
                        put("type", "string")
                        put("enum", JsonArray(
                            listOf(JsonPrimitive("single"), JsonPrimitive("parallel"), JsonPrimitive("chain"))
                        ))
                        put("description", "single: one task; parallel: run subtasks concurrently; chain: sequential handoff")
                    })
                    put("subtasks", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Subtask list for parallel/chain mode (max 8)")
                    })
                },
                required = listOf("role", "task"),
            )
        },
        execute = { input ->
            val args = input.jsonObject
            val role = args["role"]?.jsonPrimitive?.content ?: return@Tool errorText("Missing required argument: role")
            val task = args["task"]?.jsonPrimitive?.content ?: return@Tool errorText("Missing required argument: task")
            val boundary = args["boundary"]?.jsonPrimitive?.content.orEmpty()
            val context = args["context"]?.jsonPrimitive?.content.orEmpty()
            val acceptance = args["acceptance"]?.jsonPrimitive?.content.orEmpty()
            val subtasks = args["subtasks"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.filter { it.isNotBlank() }
                .orEmpty()
                .take(MAX_SUBTASKS)

            // 动态读取当前角色列表，避免工具构建时的快照过期：
            // 主 agent 可能刚通过工作区工具新建/修改了角色（含 model 字段），
            // 静态快照会导致 "Subagent role not found" 而调用失败。
            val currentRoles = runCatching { subagentManager.listSubagents() }.getOrDefault(emptyList())
            val definition = currentRoles.find { it.name == role }
                ?: return@Tool errorText(
                    "Subagent role '$role' not found. Available: " +
                        (currentRoles.joinToString { it.name }.ifEmpty { "(none installed)" })
                )

            val mode = when (args["mode"]?.jsonPrimitive?.content) {
                "parallel" -> SubagentMode.PARALLEL
                "chain" -> SubagentMode.CHAIN
                else -> SubagentMode.SINGLE
            }

            val settings = settingsStore.settingsFlow.first()
            val toolPool = toolPoolProvider().filter { it.name != "subagent" }
            val log = StringBuilder()

            val results = when (mode) {
                SubagentMode.SINGLE -> {
                    val envelope = TaskEnvelope(
                        role = role,
                        task = task,
                        boundary = boundary,
                        context = context,
                        acceptance = acceptance,
                    )
                    listOf(runAndCollect(subagentRunner, definition, envelope, settings, model, toolPool, log))
                }

                SubagentMode.PARALLEL -> {
                    val tasks = subtasks.ifEmpty { listOf(task) }
                    if (tasks.size > MAX_SUBTASKS) {
                        return@Tool errorText("Too many subtasks (max $MAX_SUBTASKS)")
                    }
                    log.appendLine("== Parallel (${tasks.size} subtasks, concurrency $PARALLEL_CONCURRENCY) ==")
                    val semaphore = Semaphore(PARALLEL_CONCURRENCY)
                    coroutineScope {
                        tasks.map { subtask ->
                            async {
                                semaphore.withPermit {
                                    val envelope = TaskEnvelope(
                                        role = role,
                                        task = subtask,
                                        boundary = boundary,
                                        context = context,
                                        acceptance = acceptance,
                                    )
                                    runAndCollect(subagentRunner, definition, envelope, settings, model, toolPool, log)
                                }
                            }
                        }.awaitAll()
                    }
                }

                SubagentMode.CHAIN -> {
                    val tasks = subtasks.ifEmpty { listOf(task) }
                    log.appendLine("== Chain (${tasks.size} steps) ==")
                    val chainResults = mutableListOf<AgentResult>()
                    var previous: AgentResult? = null
                    tasks.forEachIndexed { index, subtask ->
                        log.appendLine("-- Step ${index + 1}/${tasks.size} --")
                        val envelope = TaskEnvelope(
                            role = role,
                            task = subtask,
                            boundary = boundary,
                            context = previous?.toText?.let { "$context\n\n## Previous step result\n$it" } ?: context,
                            acceptance = acceptance,
                        )
                        val result = runAndCollect(subagentRunner, definition, envelope, settings, model, toolPool, log)
                        chainResults += result
                        previous = result
                    }
                    chainResults
                }
            }

            val text = buildString {
                append(log)
                results.forEachIndexed { index, result ->
                    if (results.size > 1) {
                        appendLine()
                        appendLine("--- Result ${index + 1}/${results.size} ---")
                    }
                    appendLine(result.toText)
                }
            }
            listOf(UIMessagePart.Text(text))
        },
    )
}

private suspend fun runAndCollect(
    runner: SubagentRunner,
    definition: SubagentMetadata,
    envelope: TaskEnvelope,
    settings: me.rerere.rikkahub.data.datastore.Settings,
    model: Model,
    toolPool: List<Tool>,
    log: StringBuilder,
): AgentResult {
    var result = AgentResult(status = AgentResultStatus.FAILED, summary = "(no result)")
    runner.run(
        definition = definition,
        envelope = envelope,
        settings = settings,
        parentModel = model,
        toolPool = toolPool.associateBy { it.name },
    ).collect { event ->
        when (event) {
            is SubagentEvent.Started -> log.appendLine("▶ [${event.role}] ${event.task}")
            is SubagentEvent.StepStarted -> Unit
            is SubagentEvent.ToolCall -> log.appendLine("  ⚙ ${event.toolName}(${event.args.take(120)})")
            is SubagentEvent.ToolResult -> log.appendLine("  ✓ ${event.toolName}: ${event.summary.take(120)}")
            is SubagentEvent.Progress -> Unit
            is SubagentEvent.Finished -> result = event.result
            is SubagentEvent.Failed -> log.appendLine("  ✗ ${event.error}")
        }
    }
    return result
}

private fun errorText(message: String): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        JsonInstant.encodeToString(
            buildJsonObject {
                put("error", JsonPrimitive(message))
            }
        )
    )
)
