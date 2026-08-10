package me.rerere.rikkahub.data.ai.tasks

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * 后台任务管理工具：查询 / 取消异步执行的工具与子代理任务。
 *
 * 后台任务由支持 `background: true` 的工具创建（subagent、workspace_shell），
 * 创建时返回 taskId；任务完成后其结果会以"后台任务回调"消息自动注入对话，
 * 无需轮询。本工具用于主动检查运行中任务的状态或取消任务。
 */
fun createBackgroundTaskTools(
    manager: BackgroundTaskManager,
): List<Tool> = listOf(
    Tool(
        name = "background_tasks",
        description = """
            Manage background tasks (async tool/subagent executions).
            Tasks are created by calling tools such as subagent or workspace_shell with background=true;
            when a task finishes, its result is AUTOMATICALLY injected into the conversation as a
            "[Background Task Callback]" message — you do NOT need to poll.
            Use this tool only to check the status of running tasks, fetch a result you missed, or cancel a task.

            Actions:
            - list: show tasks (optionally filtered by status), newest first.
            - get: show one task's full status and result by taskId.
            - cancel: cancel a running task by taskId.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", kotlinx.serialization.json.JsonArray(
                            listOf(
                                kotlinx.serialization.json.JsonPrimitive("list"),
                                kotlinx.serialization.json.JsonPrimitive("get"),
                                kotlinx.serialization.json.JsonPrimitive("cancel"),
                            )
                        ))
                        put("description", "list / get / cancel")
                    })
                    put("taskId", buildJsonObject {
                        put("type", "string")
                        put("description", "Task id (required for get/cancel), e.g. bg-1a2b3c4d")
                    })
                    put("status", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Optional filter for list: RUNNING / SUCCESS / FAILED / CANCELLED"
                        )
                    })
                },
                required = listOf("action"),
            )
        },
        execute = { input ->
            val args = input.jsonObject
            val action = args["action"]?.jsonPrimitive?.contentOrNull
                ?: return@Tool errorText("Missing required argument: action")
            val text = when (action.lowercase()) {
                "list" -> {
                    val filter = args["status"]?.jsonPrimitive?.contentOrNull?.uppercase()
                        ?.let { runCatching { BackgroundTaskStatus.valueOf(it) }.getOrNull() }
                    val tasks = manager.list(filter)
                    if (tasks.isEmpty()) {
                        "No background tasks${filter?.let { " with status $it" } ?: ""}."
                    } else {
                        buildString {
                            appendLine("Background tasks (${tasks.size}):")
                            tasks.forEach { task ->
                                appendLine()
                                appendLine("- ${task.id} [${task.kind}] ${task.status}")
                                appendLine("  title: ${task.title}")
                                appendLine("  created: ${task.createdAt.formatTime()}")
                                task.finishedAt?.let { appendLine("  finished: ${it.formatTime()}") }
                                if (task.status != BackgroundTaskStatus.RUNNING && task.result.isNotBlank()) {
                                    appendLine("  result preview: ${task.result.take(300)}")
                                }
                            }
                        }
                    }
                }

                "get" -> {
                    val taskId = args["taskId"]?.jsonPrimitive?.contentOrNull
                        ?: return@Tool errorText("Missing required argument: taskId")
                    val task = manager.get(taskId)
                        ?: return@Tool errorText("Task not found: $taskId")
                    buildString {
                        appendLine("Task ${task.id} [${task.kind}] ${task.status}")
                        appendLine("title: ${task.title}")
                        appendLine("created: ${task.createdAt.formatTime()}")
                        task.finishedAt?.let { appendLine("finished: ${it.formatTime()}") }
                        if (task.status != BackgroundTaskStatus.RUNNING) {
                            appendLine()
                            appendLine("result:")
                            appendLine(task.result.ifBlank { "(empty)" })
                        }
                    }
                }

                "cancel" -> {
                    val taskId = args["taskId"]?.jsonPrimitive?.contentOrNull
                        ?: return@Tool errorText("Missing required argument: taskId")
                    val task = manager.get(taskId)
                        ?: return@Tool errorText("Task not found: $taskId")
                    if (task.status != BackgroundTaskStatus.RUNNING) {
                        "Task $taskId is already ${task.status}, cannot cancel."
                    } else if (manager.cancel(taskId)) {
                        "Task $taskId cancellation requested."
                    } else {
                        "Failed to cancel task $taskId."
                    }
                }

                else -> return@Tool errorText("Unknown action: $action (expected list/get/cancel)")
            }
            listOf(UIMessagePart.Text(text.trim()))
        }
    ),
)

private fun Long.formatTime(): String =
    java.time.Instant.ofEpochMilli(this).toString()

private fun errorText(message: String): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        buildJsonObject { put("error", message) }.toString()
    )
)
