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
 * 段落名可通过 [sections] 参数定制（角色在 AGENT.md 的 `resultFormat` 声明），
 * `### Summary` 段内容并入 summary，其余段落进入 [AgentResult.sections]。
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

    /** 默认契约的非 Summary 段落（不含 Summary，Summary 固定特殊处理） */
    val DEFAULT_SECTIONS: List<String> = listOf("Findings", "Changes", "Risks")

    fun parse(
        raw: String,
        prefill: String? = null,
        sections: List<String> = DEFAULT_SECTIONS,
        rawMode: Boolean = false,
    ): AgentResult {
        val text = stripPrefill(raw, prefill)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return AgentResult(
                status = AgentResultStatus.SUCCESS,
                summary = "(empty output)",
                raw = rawMode,
                rawOutput = raw,
            )
        }

        // raw 模式：不做结构化解析，内容原样返回（deliverable 承载全文）
        if (rawMode) {
            return AgentResult(
                status = AgentResultStatus.SUCCESS,
                summary = trimmed,
                deliverable = trimmed,
                raw = true,
                rawOutput = raw,
            )
        }

        // 1) JSON 对象格式
        parseJson(trimmed, raw, sections)?.let { return it }

        // 2) Markdown 段落格式
        return parseMarkdown(
            text = trimmed,
            defaultStatus = extractStatus(prefill) ?: AgentResultStatus.SUCCESS,
            sections = sections,
            rawOutput = raw,
        )
    }

    // ---- JSON 格式 ----

    private fun parseJson(text: String, rawOutput: String, sections: List<String>): AgentResult? {
        if (!text.startsWith("{")) return null
        val obj = runCatching { JsonInstant.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val contractKeys = sections + "Summary"
        val hasContractKeys = obj.keys.any { key -> contractKeys.any { it.equals(key, ignoreCase = true) } }
        if (!hasContractKeys) return null

        val parsedSections = linkedMapOf<String, List<String>>()
        sections.forEach { section ->
            val values = obj.stringList(section)
            if (values.isNotEmpty()) {
                parsedSections[section] = values
            }
        }
        return AgentResult(
            status = obj.stringValue("status")?.let { parseStatus(it) }
                ?: AgentResultStatus.SUCCESS,
            summary = obj.stringValue("summary").orEmpty(),
            sections = parsedSections,
            rawOutput = rawOutput,
        ).also { it.fillLegacyFields() }
    }

    /** 按键名大小写不敏感读取字符串值 */
    private fun JsonObject.stringValue(key: String): String? {
        val actualKey = keys.firstOrNull { it.equals(key, ignoreCase = true) } ?: return null
        return this[actualKey]?.jsonPrimitive?.contentOrNull
    }

    /** 按键名大小写不敏感读取字符串列表 */
    private fun JsonObject.stringList(key: String): List<String> {
        val actualKey = keys.firstOrNull { it.equals(key, ignoreCase = true) } ?: return emptyList()
        return this[actualKey]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
    }

    // ---- Markdown 段落格式 ----

    private fun parseMarkdown(
        text: String,
        defaultStatus: AgentResultStatus,
        sections: List<String>,
        rawOutput: String,
    ): AgentResult {
        val lines = text.lines()
        val titleIndex = lines.indexOfFirst { it.isResultTitle() }

        val status = if (titleIndex >= 0) {
            extractStatus(lines[titleIndex]) ?: defaultStatus
        } else {
            defaultStatus
        }

        // 结果块之前的完整产出物（writer 类角色的代码/文档/内容）
        val deliverable = if (titleIndex > 0) {
            lines.take(titleIndex).joinToString("\n").trim()
        } else {
            ""
        }

        // 分段收集：标题行之后、或（无标题时）全文，按 ### 段标题切分
        val start = if (titleIndex >= 0) titleIndex + 1 else 0
        val sectionItems = linkedMapOf<String, MutableList<String>>()
        var currentSection: String? = null
        var summaryLines = mutableListOf<String>()
        val summarySection = sections.firstOrNull { it.equals("Summary", ignoreCase = true) }

        for (i in start until lines.size) {
            val line = lines[i].trim()
            val section = line.sectionName(sections)
            if (section != null) {
                currentSection = section
                continue
            }
            if (line.isBlank()) continue
            if (currentSection == null) {
                summaryLines.add(line)
            } else if (currentSection.equals("Summary", ignoreCase = true)) {
                // `### Summary` 段内容并入 summary
                summaryLines.add(line)
            } else {
                sectionItems.getOrPut(currentSection) { mutableListOf() }.add(line)
            }
        }

        val parsedSections = linkedMapOf<String, List<String>>()
        sections.forEach { section ->
            val items = sectionItems[section].orEmpty().map { it.stripListMarker() }
            if (items.isNotEmpty()) {
                parsedSections[section] = items
            }
        }

        return AgentResult(
            status = status,
            summary = summaryLines.joinToString("\n").trim(),
            deliverable = deliverable,
            sections = parsedSections,
            rawOutput = rawOutput,
        ).also { it.fillLegacyFields() }
    }

    /** 把默认契约段 Findings/Changes/Risks 同步到兼容字段 */
    private fun AgentResult.fillLegacyFields(): AgentResult {
        val findings = sections.entries.firstOrNull { it.key.equals("Findings", ignoreCase = true) }?.value.orEmpty()
        val changes = sections.entries.firstOrNull { it.key.equals("Changes", ignoreCase = true) }?.value.orEmpty()
        val risks = sections.entries.firstOrNull { it.key.equals("Risks", ignoreCase = true) }?.value.orEmpty()
        return if (findings.isNotEmpty() || changes.isNotEmpty() || risks.isNotEmpty()) {
            copy(findings = findings, changes = changes, risks = risks)
        } else {
            this
        }
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

    /** 匹配 `### Summary` / `### Findings` / `### Changes` / `### Risks` 等段标题（按角色契约段落名，大小写不敏感） */
    private fun String.sectionName(sections: List<String>): String? {
        val compact = trim().replace("*", "")
        if (!compact.startsWith("###", ignoreCase = true)) return null
        val name = compact.removePrefix("###").trim().trimEnd(':').trim()
        return (sections + "Summary").firstOrNull { it.equals(name, ignoreCase = true) }
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
}

/**
 * 子代理输出契约：注入 system prompt 与 prefill 引导，
 * 使子代理最终输出可被 [StructuredResultParser] 稳定解析。
 *
 * 角色可在 AGENT.md frontmatter 中用 `resultFormat` 自定义输出模式：
 * - `resultFormat: raw`                      纯文本输出，不注入任何契约、不做结构化包装（适合 NSFW/小说/散文等产出）
 * - `resultFormat: Summary, Bugs, Security`  自定义段落名（逗号分隔）
 * - 未声明时使用默认契约（Summary / Findings / Changes / Risks）
 */
object SubagentResultContract {

    const val TITLE: String = "## Agent Result (SUCCESS)"

    /** `resultFormat: raw` 特殊值：纯文本输出，跳过所有结构化处理 */
    private const val RAW_MARKER = "raw"

    /** 默认契约：Summary + Findings/Changes/Risks */
    fun default(): RoleContract = forRole(null)

    /**
     * 根据角色的 `resultFormat` 生成契约。
     *
     * - `raw`（大小写不敏感）→ 纯文本模式：无契约注入、无 prefill、无结构化解析
     * - 逗号分隔段落名 → 按段落名生成契约；未声明或解析为空时回退默认契约
     * 段落名保留用户写法，匹配时大小写不敏感；Summary 段自动特殊处理（内容并入 summary）。
     */
    fun forRole(resultFormat: String?): RoleContract {
        if (resultFormat?.trim()?.equals(RAW_MARKER, ignoreCase = true) == true) {
            return RoleContract(
                sections = emptyList(),
                systemPrompt = "",
                prefill = "",
                raw = true,
            )
        }
        val sections = resultFormat
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
            .let { list ->
                if (list.isEmpty()) DEFAULT_SECTIONS else list
            }
        return RoleContract(
            sections = sections,
            systemPrompt = buildSystemPrompt(sections),
            prefill = PREFILL,
        )
    }

    private val DEFAULT_SECTIONS: List<String> = listOf("Findings", "Changes", "Risks")

    private fun buildSystemPrompt(sections: List<String>): String = buildString {
        appendLine("## Output Contract")
        appendLine("Write your FULL deliverable (code, document, content, etc.) FIRST — do not abbreviate it. ")
        appendLine("Then end your response with a structured result block in EXACTLY this format:")
        appendLine()
        appendLine(TITLE)
        appendLine()
        appendLine("<concise summary of what was done>")
        sections.forEach { section ->
            if (!section.equals("Summary", ignoreCase = true)) {
                appendLine()
                appendLine("### $section")
                appendLine("- <item>")
            }
        }
        appendLine()
        appendLine("Rules:")
        appendLine("- Replace SUCCESS with FAILED if the task could not be completed.")
        appendLine("- Omit any section that has no items.")
        appendLine("- Keep every item on one line prefixed with \"- \".")
        appendLine("- Only the result block is parsed: the full deliverable and any notes BEFORE it are preserved and returned to the caller.")
        appendLine("- The result block summary must stay SHORT — never replace the deliverable with it.")
    }.trim()

    /** assistant 预填充消息：引导模型从结构化结果开头续写 */
    const val PREFILL: String = "## Agent Result (SUCCESS)\n\n"
}

/** 角色级输出契约：自定义段落名 + 注入 system prompt 的格式说明 + prefill + raw 模式标记 */
data class RoleContract(
    val sections: List<String>,
    val systemPrompt: String,
    val prefill: String,
    val raw: Boolean = false,
)
