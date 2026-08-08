package me.rerere.rikkahub.data.ai.subagent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant

/**
 * 子代理结构化结果解析器。
 *
 * 支持两种输出契约格式：
 *
 * 1. Markdown 段落格式（默认契约，与 [AgentResult.toText] 输出 round-trip 兼容）：
 * ```
 * ## Agent Result (SUCCESS)
 *
 * <summary 文本，可多行>
 *
 * ### Findings
 * - key finding
 *
 * ### Changes
 * - file or behavior change
 *
 * ### Risks
 * - risk or caveat
 * ```
 * 标题也兼容 `**Agent Result (SUCCESS)**` 加粗形式；状态括号可省略（默认 SUCCESS）。
 *
 * 2. JSON 对象格式：
 * ```
 * {"status":"SUCCESS","summary":"...","findings":[...],"changes":[...],"risks":[...]}
 * ```
 *
 * 解析失败时回退：全文作为 summary（兼容无契约输出的旧行为）。
 * 若传入 [prefill]（assistant 预填充消息文本），解析前会先剥离该前缀，
 * 此时标题可能已被 prefill 消耗，状态取 prefill 中声明的状态。
 */
object StructuredResultParser {

    private val STATUS_NAMES = AgentResultStatus.entries.mapTo(HashSet()) { it.name }

    private val SECTION_NAMES = setOf("Summary", "Findings", "Changes", "Risks")

    fun parse(raw: String, prefill: String? = null): AgentResult {
        val text = stripPrefill(raw, prefill)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return AgentResult(
                status = AgentResultStatus.SUCCESS,
                summary = "(empty output)",
                rawOutput = raw,
            )
        }

        // 1) JSON 对象格式
        parseJson(trimmed, raw)?.let { return it }

        // 2) Markdown 段落格式
        return parseMarkdown(
            text = trimmed,
            defaultStatus = extractStatus(prefill) ?: AgentResultStatus.SUCCESS,
            rawOutput = raw,
        )
    }

    // ---- JSON 格式 ----

    private fun parseJson(text: String, rawOutput: String): AgentResult? {
        if (!text.startsWith("{")) return null
        val obj = runCatching { JsonInstant.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val hasContractKeys = obj.keys.any { it in CONTRACT_KEYS }
        if (!hasContractKeys) return null
        return AgentResult(
            status = obj["status"]?.jsonPrimitive?.contentOrNull?.let { parseStatus(it) }
                ?: AgentResultStatus.SUCCESS,
            summary = obj["summary"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            findings = obj.stringList("findings"),
            changes = obj.stringList("changes"),
            risks = obj.stringList("risks"),
            rawOutput = rawOutput,
        )
    }

    private fun JsonObject.stringList(key: String): List<String> =
        this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

    // ---- Markdown 段落格式 ----

    private fun parseMarkdown(
        text: String,
        defaultStatus: AgentResultStatus,
        rawOutput: String,
    ): AgentResult {
        val lines = text.lines()
        val titleIndex = lines.indexOfFirst { it.isResultTitle() }

        val status = if (titleIndex >= 0) {
            extractStatus(lines[titleIndex]) ?: defaultStatus
        } else {
            defaultStatus
        }

        // 分段收集：标题行之后、或（无标题时）全文，按 ### 段标题切分
        val start = if (titleIndex >= 0) titleIndex + 1 else 0
        val sections = linkedMapOf<String, MutableList<String>>()
        var currentSection: String? = null
        var summaryLines = mutableListOf<String>()

        for (i in start until lines.size) {
            val line = lines[i].trim()
            val section = line.sectionName()
            if (section != null) {
                currentSection = section
                continue
            }
            if (line.isBlank()) continue
            if (currentSection == null) {
                summaryLines.add(line)
            } else {
                sections.getOrPut(currentSection) { mutableListOf() }.add(line)
            }
        }

        return AgentResult(
            status = status,
            summary = summaryLines.joinToString("\n").trim(),
            findings = sections["Findings"].orEmpty().map { it.stripListMarker() },
            changes = sections["Changes"].orEmpty().map { it.stripListMarker() },
            risks = sections["Risks"].orEmpty().map { it.stripListMarker() },
            rawOutput = rawOutput,
        )
    }

    /** 匹配 `## Agent Result (SUCCESS)` / `## Agent Result` / `**Agent Result (SUCCESS)**` 等标题行 */
    private fun String.isResultTitle(): Boolean {
        // 去掉加粗星号与前导 #，兼容 `## Agent Result (...)` 与 `**Agent Result (...)**` 两种写法
        val normalized = trim().replace("*", "").trimStart('#').trim()
        if (!normalized.startsWith("Agent Result", ignoreCase = true)) return false
        val rest = normalized.removePrefix("Agent Result").trim()
        return rest.isEmpty() || (rest.startsWith("(") && rest.endsWith(")"))
    }

    /** 从标题或 prefill 文本中提取 `(STATUS)` 状态 */
    private fun extractStatus(text: String?): AgentResultStatus? {
        if (text.isNullOrBlank()) return null
        val start = text.indexOf('(')
        val end = text.indexOf(')', start)
        if (start < 0 || end <= start) return null
        return parseStatus(text.substring(start + 1, end))
    }

    private fun parseStatus(raw: String): AgentResultStatus? {
        val upper = raw.trim().uppercase()
        return AgentResultStatus.entries.firstOrNull { it.name == upper }
    }

    /** 匹配 `### Summary` / `### Findings` / `### Changes` / `### Risks` 段标题（大小写不敏感） */
    private fun String.sectionName(): String? {
        val compact = trim().replace("*", "")
        if (!compact.startsWith("###", ignoreCase = true)) return null
        val name = compact.removePrefix("###").trim().trimEnd(':').trim()
        return SECTION_NAMES.firstOrNull { it.equals(name, ignoreCase = true) }
    }

    /** 去掉 `- ` / `* ` / `1. ` 等列表标记 */
    private fun String.stripListMarker(): String {
        val trimmed = trim()
        return when {
            trimmed.startsWith("- ") -> trimmed.removePrefix("- ").trim()
            trimmed.startsWith("* ") -> trimmed.removePrefix("* ").trim()
            trimmed.matches(Regex("\\d+\\.\\s.*")) -> trimmed.replaceFirst(Regex("\\d+\\.\\s"), "")
            trimmed.startsWith("-") || trimmed.startsWith("*") -> trimmed.drop(1).trim()
            else -> trimmed
        }
    }

    private fun stripPrefill(raw: String, prefill: String?): String {
        if (prefill.isNullOrBlank()) return raw
        // 1) 标准情况：模型直接续写 prefill
        if (raw.startsWith(prefill)) return raw.removePrefix(prefill).trimStart()
        // 2) 模型重新输出了标题：从第一个标题行截取
        val lines = raw.lines()
        val titleIndex = lines.indexOfFirst { it.isResultTitle() }
        if (titleIndex > 0) {
            return lines.drop(titleIndex).joinToString("\n")
        }
        return raw
    }

    private val CONTRACT_KEYS = setOf("status", "summary", "findings", "changes", "risks")
}

/**
 * 子代理输出契约：注入 system prompt 与 prefill 引导，
 * 使子代理最终输出可被 [StructuredResultParser] 稳定解析。
 */
object SubagentResultContract {

    /** 追加到子代理 system prompt 尾部的格式说明 */
    val SYSTEM_PROMPT: String = """
        ## Output Contract
        When the task is finished, end your response with a structured result in EXACTLY this format:

        ## Agent Result (SUCCESS)

        <concise summary of what was done>

        ### Findings
        - <key finding>

        ### Changes
        - <file or behavior change>

        ### Risks
        - <risk or caveat>

        Rules:
        - Replace SUCCESS with FAILED if the task could not be completed.
        - Omit any section that has no items (e.g. no Risks).
        - Keep every item on one line prefixed with "- ".
        - You may include working notes BEFORE the "## Agent Result" block; only the block is parsed.
    """.trimIndent()

    /** assistant 预填充消息：引导模型从结构化结果开头续写 */
    const val PREFILL: String = "## Agent Result (SUCCESS)\n\n"
}
