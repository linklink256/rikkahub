package me.rerere.rikkahub.data.ai.subagent

import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SubagentManager

/**
 * 子代理列表工具：只读查询已安装的子代理角色。
 *
 * 子代理角色的**管理**（创建 / 读取 / 修改 / 删除）不依赖专用工具：
 * 每个角色就是 `/agents/<role-name>/AGENT.md` 一个文件，已挂载到工作区，
 * 主 agent 直接用原生工作区工具管理：
 * - 查看: `workspace_read_file` 读 `/agents/<name>/AGENT.md`
 * - 创建/修改: `workspace_write_file` / `workspace_edit_file` 写该文件
 * - 删除: `workspace_shell` 执行 `rm -rf /agents/<name>`
 * 与 skills 的 `/skills` 映射保持一致。
 */
fun createSubagentManagementTools(
    subagentManager: SubagentManager,
): List<Tool> = listOf(
    Tool(
        name = "list_subagents",
        description = """
            List all installed subagent roles (name, description, group, tools whitelist, model, resultFormat).
            Use this when the user asks what subagents are available, or before delegating/editing.
            To view, create, update or delete a role's definition, use the native workspace file tools
            on `/agents/<role-name>/AGENT.md` (mounted in the workspace).
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {},
            )
        },
        execute = {
            val list = subagentManager.listSubagents()
            val text = if (list.isEmpty()) {
                "No subagents installed. Create one by writing an AGENT.md under /agents/<role-name>/ " +
                    "with the workspace tools (e.g. `workspace_write_file`), or ask the user to add one " +
                    "in Extensions > Subagents."
            } else {
                buildString {
                    appendLine("Installed subagents (${list.size}):")
                    list.groupBy { it.group }.forEach { (group, members) ->
                        appendLine("• Group \"${group}\":")
                        members.forEach { s ->
                            appendLine("  - ${s.name}: ${s.description}")
                            appendLine("    tools: ${s.tools.joinToString(", ").ifEmpty { "(all)" }}")
                            appendLine("    model: ${s.model ?: "(inherit main agent)"}")
                            appendLine("    resultFormat: ${s.resultFormat ?: "(default: Summary, Findings, Changes, Risks)"}")
                        }
                    }
                    appendLine()
                    appendLine(
                        "Definitions live at /agents/<role-name>/AGENT.md. Manage them with the native " +
                            "workspace file tools (read / write / edit / shell rm) — no dedicated tools needed."
                    )
                }
            }
            listOf(UIMessagePart.Text(text))
        }
    ),
)
