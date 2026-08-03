package me.rerere.rikkahub.data.ai.subagent

import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * 系统设置查询工具：让主 agent 可以查看当前配置的供应商 / 模型，
 * 以便为子代理（subagent）指定合适的 `model` 字段。
 *
 * 输出格式与 SubagentRunner.resolveModel 的解析逻辑兼容：
 * - `model: gpt-4o`                  只按模型 ID/显示名匹配
 * - `model: OpenAI/gpt-4o`           精确指定供应商（供应商名/模型ID）
 * - 留空则继承主 agent 模型
 *
 * 注意：只输出供应商名称与模型元数据，**不会**暴露 apiKey / baseUrl 等敏感配置。
 */
fun createProviderSettingsTools(
    settingsStore: SettingsStore,
): List<Tool> = listOf(
    Tool(
        name = "list_provider_models",
        description = """
            List the AI providers and chat models currently configured in the app settings.
            Use this to see which model identifiers are available before creating or updating a
            subagent, or before delegating a task, so you can pick the right model for a role.
            Output format for the `model` field is 'ProviderName/modelId', e.g. 'OpenAI/gpt-4o'.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {},
            )
        },
        execute = {
            val settings = settingsStore.settingsFlow.value
            val text = buildString {
                val providers = settings.providers.filter { it.enabled }
                if (providers.isEmpty()) {
                    appendLine("No enabled providers configured. Ask the user to add a provider in Settings > Providers.")
                } else {
                    appendLine("Enabled providers (${providers.size}):")
                    providers.forEach { provider ->
                        appendLine("• ${provider.name}")
                        if (provider.models.isEmpty()) {
                            appendLine("    (no models configured)")
                        } else {
                            provider.models.forEach { model ->
                                val modelPart = model.modelId.ifBlank { model.displayName }
                                val display = model.displayName.ifBlank { model.modelId }
                                val chatTag = if (model.type.name == "CHAT") "chat" else "non-chat"
                                val abilityTag = model.abilities.joinToString(",") { it.name.lowercase() }
                                appendLine(
                                    "    - ${provider.name}/$modelPart  ($display) [$chatTag" +
                                        if (abilityTag.isNotBlank()) ", $abilityTag]" else "]"
                                )
                            }
                        }
                    }
                    appendLine()
                    appendLine(
                        "To use a model for a subagent, set its AGENT.md `model` field to " +
                            "'ProviderName/modelId' (e.g. 'OpenAI/gpt-4o'), or omit it to inherit the main agent model."
                    )
                }
            }
            listOf(UIMessagePart.Text(text))
        }
    ),
)
