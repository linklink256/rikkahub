package me.rerere.rikkahub.data.ai.subagent

import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.effectiveReasoningEffort
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
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
    settingsStore: SettingsStore,
    enabledSubagents: Set<String> = emptySet(),
): List<Tool> = listOf(
    Tool(
        name = "list_subagents",
        description = """
            List all installed subagent roles (name, description, group, tools whitelist, model, resultFormat, enabled status).
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
            // 空集 = 全部启用；含哨兵 = 全部禁用；非空 = 白名单
            val enabled = enabledSubagents
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
                            val status = if (enabled.isEmpty() || s.name in enabled) {
                                "enabled"
                            } else {
                                "disabled (not in assistant's enabledSubagents)"
                            }
                            appendLine("  - ${s.name}: ${s.description} [$status]")
                            appendLine(
                                "    tools: " + when {
                                    s.toolsDisabled -> "(none - all disabled)"
                                    s.tools.isEmpty() -> "(all inherited)"
                                    else -> s.tools.joinToString(", ")
                                }
                            )
                            appendLine("    model: ${s.model ?: "(inherit main agent)"}")
                            appendLine("    reasoning: ${s.reasoningLevel?.name ?: "(inherit default OFF)"}")
                            appendLine("    maxSteps: ${s.maxSteps ?: 30}")
                            appendLine("    stepTimeout: ${s.stepTimeoutMillis?.div(1000) ?: 120}s")
                            appendLine("    toolOutputLimit: ${s.toolOutputLimit ?: 20000}")
                            appendLine("    streaming: ${s.streaming ?: false}")
                            // 实际生效 effort：解析角色模型（无 model 时无法确定 → 标注继承）
                            appendLine("    effective effort: ${resolveEffortLine(s, settingsStore)}")
                            appendLine("    resultFormat: ${s.resultFormat ?: "(default: Summary, Findings, Changes, Risks)"}")
                        }
                    }
                    appendLine()
                    appendLine(
                        "Definitions live at /agents/<role-name>/AGENT.md. Manage them with the native " +
                            "workspace file tools (read / write / edit / shell rm) — no dedicated tools needed."
                    )
                    appendLine(
                        "Session memory (platform auto): each role persists a memory file at " +
                            "/workspace/.cache/subagent-memory/<name>.md across calls. " +
                            "Memory may be stale — for time-sensitive data ask the subagent to read the actual file, never answer from memory alone."
                    )
                    appendLine(
                        "Enabled status comes from the assistant's enabledSubagents list (empty = all enabled). " +
                            "A role listed as disabled cannot be delegated to via the subagent tool."
                    )
                }
            }
            listOf(UIMessagePart.Text(text))
        }
    ),
)

/**
 * 解析子代理角色实际生效的 effort 值（供 list_subagents 展示）。
 *
 * - 角色未声明 model → 继承主 agent 模型，无法在此确定 → 标注 "(inherit main agent, unknown)"
 * - 角色声明的模型未在设置中配置 → "(model not configured)"
 * - 模型无 REASONING 能力 → "(no effect: model lacks REASONING ability)"
 * - 正常 → 显示实际 effort + 截断说明
 */
private fun resolveEffortLine(
    s: me.rerere.rikkahub.data.files.SubagentMetadata,
    settingsStore: SettingsStore,
): String {
    val level = s.reasoningLevel ?: me.rerere.ai.core.ReasoningLevel.OFF
    val modelName = s.model?.trim().orEmpty()
    if (modelName.isEmpty()) {
        return "$level (inherit main agent model, unknown)"
    }
    // 解析模型：支持 'Provider/Model' / 'Provider:Model' / 裸模型名
    val slashIndex = modelName.indexOf('/')
    val colonIndex = modelName.indexOf(':')
    val sepIndex = listOf(slashIndex, colonIndex).filter { it >= 0 }.minOrNull()
    val providerFilter = if (sepIndex != null) modelName.substring(0, sepIndex).trim() else null
    val modelPart = if (sepIndex != null) modelName.substring(sepIndex + 1).trim() else modelName

    val settings = settingsStore.settingsFlow.value
    var host: String? = null
    val model = settings.providers.firstNotNullOfOrNull { provider ->
        if (providerFilter != null && !provider.name.equals(providerFilter, ignoreCase = true)) {
            return@firstNotNullOfOrNull null
        }
        val found = provider.models.firstOrNull { it.modelId == modelPart || it.displayName == modelPart }
        if (found != null) {
            // 记录供应商 baseUrl 供 effort 映射（DeepSeek 等特殊规则）
            host = when (provider) {
                is me.rerere.ai.provider.ProviderSetting.OpenAI -> provider.baseUrl
                else -> null
            }
        }
        found
    }
    if (model == null) {
        return "$level (model '$modelName' not configured)"
    }
    val effective = model.effectiveReasoningEffort(level, host)
        ?: return "$level (no effect: model lacks REASONING ability)"
    return if (effective.isCapped) {
        "${effective.value} (${effective.note})"
    } else {
        effective.value
    }
}
