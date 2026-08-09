package me.rerere.ai.provider

import me.rerere.ai.core.ReasoningLevel

/**
 * 计算"实际生效"的 reasoning effort 值。
 *
 * 用户在 UI / AGENT.md 里选的 ReasoningLevel 是抽象档位，最终发送到 API 的参数
 * 取决于模型能力与供应商支持度。本函数给出与 providers 各实现一致的映射结果，
 * 用于 UI 预览与 list_subagents 展示，避免"设置 high 实际没生效"的困惑。
 *
 * 映射依据官方文档（2026-08）：
 * - DeepSeek V4（api.deepseek.com）：reasoning_effort 支持 low/high/max；
 *   medium/xhigh 为兼容值：flash: xhigh→high；pro: xhigh→max、low→high
 *   （https://api-docs.deepseek.com/api/create-chat-completion）
 * - OpenAI 新模型（gpt-5.x 等）：effort 支持 none/minimal/low/medium/high/xhigh/max，模型相关
 * - Claude（Opus 4.7+）：effort 支持 low/medium/high/xhigh/max，模型相关
 *
 * @param host 供应商 baseUrl（用于识别 DeepSeek 等特殊映射；null 时按通用模型处理）
 * @return 实际发送的 effort 字符串，以及说明；模型无 REASONING 能力时返回 null（完全不生效）。
 */
fun Model.effectiveReasoningEffort(level: ReasoningLevel, host: String? = null): EffectiveEffort? {
    // 模型没有推理能力 → 任何档位都不生效
    if (!abilities.contains(ModelAbility.REASONING)) {
        return null
    }
    val isDeepSeek = host?.contains("api.deepseek.com") == true ||
        modelId.lowercase().contains("deepseek-v4")
    return when {
        isDeepSeek -> deepSeekEffort(level)
        else -> genericEffort(level)
    }
}

/** DeepSeek V4 官方映射（reasoning_effort 只支持 low/high/max；medium/xhigh 为兼容值） */
private fun Model.deepSeekEffort(level: ReasoningLevel): EffectiveEffort {
    val isFlash = modelId.lowercase().contains("flash")
    return when (level) {
        ReasoningLevel.OFF -> EffectiveEffort("none", "thinking disabled")
        ReasoningLevel.AUTO -> EffectiveEffort("auto", "provider decides (no param sent)")
        ReasoningLevel.LOW -> if (isFlash) {
            EffectiveEffort("low", null)
        } else {
            EffectiveEffort("high", "deepseek-v4-pro maps low to high")
        }
        ReasoningLevel.MEDIUM -> EffectiveEffort("high", "deepseek maps medium to high (compat)")
        ReasoningLevel.HIGH -> EffectiveEffort("high", null)
        ReasoningLevel.XHIGH -> if (isFlash) {
            EffectiveEffort("high", "deepseek-v4-flash maps xhigh to high (compat)")
        } else {
            EffectiveEffort("max", "deepseek-v4-pro maps xhigh to max")
        }
        ReasoningLevel.MAX -> EffectiveEffort("max", null)
    }
}

/** 通用映射：OpenAI / Claude 等新模型原生支持 xhigh/max（模型相关），原样透传 */
private fun genericEffort(level: ReasoningLevel): EffectiveEffort = when (level) {
    ReasoningLevel.OFF -> EffectiveEffort("none", "thinking disabled")
    ReasoningLevel.AUTO -> EffectiveEffort("auto", "provider decides (no param sent)")
    ReasoningLevel.LOW -> EffectiveEffort("low", null)
    ReasoningLevel.MEDIUM -> EffectiveEffort("medium", null)
    ReasoningLevel.HIGH -> EffectiveEffort("high", null)
    ReasoningLevel.XHIGH -> EffectiveEffort(
        "xhigh",
        "xhigh is supported by newer models (gpt-5.x, Claude Opus 4.7+); older models may reject it"
    )
    ReasoningLevel.MAX -> EffectiveEffort(
        "max",
        "max is supported by newer models (gpt-5.x, Claude Opus 4.7+, deepseek-v4); older models may reject it"
    )
}

/** 实际生效的 effort 值及说明 */
data class EffectiveEffort(
    val value: String,
    val note: String? = null,
) {
    /** 是否有需要用户注意的说明（兼容映射 / 模型相关警告） */
    val isCapped: Boolean get() = note != null
}
