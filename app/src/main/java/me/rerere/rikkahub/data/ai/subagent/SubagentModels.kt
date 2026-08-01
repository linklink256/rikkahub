package me.rerere.rikkahub.data.ai.subagent

import kotlinx.serialization.Serializable
import me.rerere.ai.core.TokenUsage

/**
 * 子代理委派模式。
 */
enum class SubagentMode {
    /** 单任务：一个子 agent 跑一个任务 */
    SINGLE,

    /** 并行：一个角色跑多个子任务（有界并发） */
    PARALLEL,

    /** 链式：串行 handoff，上一步结果注入下一步 */
    CHAIN,
}

/**
 * 父 agent 发给子 agent 的任务包。
 *
 * 子 agent 不继承父会话历史，只拿到这个最小上下文包。
 */
data class TaskEnvelope(
    val role: String,
    val task: String,
    val boundary: String = "",
    val context: String = "",
    val acceptance: String = "",
)

/**
 * 子 agent 返回的结构化结果。
 */
@Serializable
data class AgentResult(
    val status: AgentResultStatus = AgentResultStatus.SUCCESS,
    val summary: String = "",
    val findings: List<String> = emptyList(),
    val changes: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
    val usage: TokenUsage? = null,
) {
    val toText: String
        get() = buildString {
            appendLine("## Agent Result (${status.name})")
            if (summary.isNotBlank()) appendLine(summary)
            if (findings.isNotEmpty()) {
                appendLine()
                appendLine("### Findings")
                findings.forEach { appendLine("- $it") }
            }
            if (changes.isNotEmpty()) {
                appendLine()
                appendLine("### Changes")
                changes.forEach { appendLine("- $it") }
            }
            if (risks.isNotEmpty()) {
                appendLine()
                appendLine("### Risks")
                risks.forEach { appendLine("- $it") }
            }
        }.trim()
}

enum class AgentResultStatus {
    SUCCESS,
    FAILED,
    TIMEOUT,
    CANCELLED,
}

/**
 * 子 agent 运行过程中的流式事件，用于 UI 展示与父会话感知。
 */
sealed class SubagentEvent {
    data class Started(
        val agentId: String,
        val role: String,
        val task: String,
    ) : SubagentEvent()

    data class StepStarted(
        val agentId: String,
        val step: Int,
        val maxSteps: Int,
    ) : SubagentEvent()

    data class ToolCall(
        val agentId: String,
        val toolName: String,
        val args: String,
    ) : SubagentEvent()

    data class ToolResult(
        val agentId: String,
        val toolName: String,
        val summary: String,
    ) : SubagentEvent()

    data class Progress(
        val agentId: String,
        val text: String,
    ) : SubagentEvent()

    data class Finished(
        val agentId: String,
        val result: AgentResult,
    ) : SubagentEvent()

    data class Failed(
        val agentId: String,
        val error: String,
    ) : SubagentEvent()
}
