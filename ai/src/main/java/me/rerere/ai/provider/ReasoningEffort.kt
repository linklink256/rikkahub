package me.rerere.ai.provider

import me.rerere.ai.core.ModelAbility
import me.rerere.ai.core.ReasoningLevel

/**
 * 计算"实际生效"的 reasoning effort 值。
 *
 * 用户在 UI / AGENT.md 里选的 ReasoningLevel 是抽象档位，最终发送到 API 的参数
 * 取决于模型能力与供应商支持度。本函数给出与 providers 各实现一致的映射结果，
 * 用于 UI 预览与 list_subagents 展示，避免"设置 high 实际没生效"的困惑。
 *
 * @return 实际发送的 effort 字符串（如 "low"/"medium"/"high"/"xhigh"/"max"/"none"），
 *         以及是否真正生效的说明；模型无 REASONING 能力时返回 null（完全不生效）。
 */
fun Model.effectiveReasoningEffort(level: ReasoningLevel): EffectiveEffort? {
    // 模型没有推理能力 → 任何档位都不生效
    if (!abilities.contains(ModelAbility.REASONING)) {
        return null
    }
    return when (level) {
        ReasoningLevel.OFF -> EffectiveEffort("none", "thinking disabled")
        ReasoningLevel.AUTO -> EffectiveEffort("auto", "provider decides (no param sent)")
        ReasoningLevel.LOW -> EffectiveEffort("low", null)
        ReasoningLevel.MEDIUM -> EffectiveEffort("medium", null)
        ReasoningLevel.HIGH -> EffectiveEffort("high", null)
        // 大多数 API（OpenAI 官方 / Claude / OpenRouter）只支持 low/medium/high，
        // xhigh/max 会被截断到 high；仅部分网关（NVIDIA deepseek-v4、opencode.ai）
        // 与 Gemini 预算模式可透传/映射更高档位
        ReasoningLevel.XHIGH -> EffectiveEffort(
            "high",
            "xhigh not supported by most APIs, capped to high"
        )
        ReasoningLevel.MAX -> EffectiveEffort(
            "high",
            "max not supported by most APIs, capped to high"
        )
    }
}

/** 实际生效的 effort 值及说明 */
data class EffectiveEffort(
    val value: String,
    val note: String? = null,
) {
    /** 是否被截断/降级（xhigh/max → high 等） */
    val isCapped: Boolean get() = note != null
}
