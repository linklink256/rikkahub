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
    val availableRoles = runCatching { subagentManager.listSubagents() }.getOrDefault(emptyList())

    return Tool(
        name = "subagent",
        description = """
            Delegate a focused task to a subagent. Subagents run in an isolated session with their own
            system prompt, tool whitelist and optional model; they do NOT inherit the conversation history.
            Use for large, focused or parallelizable work (research, planning, review, refactoring) to keep
            the main context clean. Returns a structured result.
            Available subagents: ${availableRoles.joinToString { it.name }}
            Install more roles in Extensions > Subagents.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("role", buildJsonObject {
                        put("type", "string")
                        put("description", "Subagent role. Available: ${availableRoles.joinToString { it.name }}")
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

            val definition = availableRoles.find { it.name == role }
                ?: return@Tool errorText(
                    "Subagent role '$role' not found. Available: " +
                        (availableRoles.joinToString { it.name }.ifEmpty { "(none installed)" })
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
