package me.rerere.rikkahub.data.ai.subagent

import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.effectiveReasoningEffort
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * 系统设置查询工具：让主 agent 可以查看当前配置的供应商 / 模型，
 * 以便为子代理（subagent）指定合适的 `model` 字段。
 *
 * 输出格式与 SubagentRunner.resolveModel 的解析逻辑兼容：
 * - `model: openai:gpt-4o` / `OpenAI/gpt-4o`   精确指定供应商与模型（供应商名:模型ID 或 供应商名/模型ID）
 * - `model: gpt-4o`                            只按模型 ID/显示名匹配
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
            Output format for the `model` field is 'ProviderName:modelId' or 'ProviderName/modelId',
            e.g. 'openai:gpt-4o' / 'OpenAI/gpt-4o'; a bare model id like 'gpt-4o' also works.
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
                                    "    - ${provider.name}:$modelPart  ($display) [$chatTag" +
                                        if (abilityTag.isNotBlank()) ", $abilityTag]" else "]"
                                )
                                // 推理模型：附加 effort 支持说明（基于供应商 baseUrl 的官方映射）
                                if (model.abilities.contains(ModelAbility.REASONING)) {
                                    val host = (provider as? ProviderSetting.OpenAI)?.baseUrl
                                    val maxEffort = model.effectiveReasoningEffort(ReasoningLevel.MAX, host)
                                    val xhighEffort = model.effectiveReasoningEffort(ReasoningLevel.XHIGH, host)
                                    appendLine(
                                        "      reasoning effort: max->${maxEffort?.value ?: "?"}, " +
                                            "xhigh->${xhighEffort?.value ?: "?"}, " +
                                            "high->${model.effectiveReasoningEffort(ReasoningLevel.HIGH, host)?.value ?: "?"}, " +
                                            "low->${model.effectiveReasoningEffort(ReasoningLevel.LOW, host)?.value ?: "?"}"
                                    )
                                }
                            }
                        }
                    }
                    appendLine()
                    appendLine(
                        "To use a model for a subagent, set its AGENT.md `model` field to " +
                            "'ProviderName:modelId' or 'ProviderName/modelId' (e.g. 'openai:gpt-4o' / 'OpenAI/gpt-4o'), " +
                            "or just the model id (e.g. 'gpt-4o'); omit it to inherit the main agent model."
                    )
                }
            }
            listOf(UIMessagePart.Text(text))
        }
    ),
)
