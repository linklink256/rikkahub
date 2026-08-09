package me.rerere.rikkahub.data.ai.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredResultParserTest {

    // ---- Markdown 段落格式 ----

    @Test
    fun `full markdown result with all sections`() {
        val raw = """
            ## Agent Result (SUCCESS)

            Done with the research.

            ### Findings
            - Model A is faster
            - Model B is cheaper

            ### Changes
            - updated config.json

            ### Risks
            - API rate limit may be hit
        """.trimIndent()

        val result = StructuredResultParser.parse(raw)

        assertEquals(AgentResultStatus.SUCCESS, result.status)
        assertEquals("Done with the research.", result.summary)
        assertEquals(listOf("Model A is faster", "Model B is cheaper"), result.findings)
        assertEquals(listOf("updated config.json"), result.changes)
        assertEquals(listOf("API rate limit may be hit"), result.risks)
        assertEquals(raw, result.rawOutput)
    }

    @Test
    fun `failed status is extracted from title`() {
        val raw = """
            ## Agent Result (FAILED)

            Could not reach the server.
        """.trimIndent()

        val result = StructuredResultParser.parse(raw)

        assertEquals(AgentResultStatus.FAILED, result.status)
        assertEquals("Could not reach the server.", result.summary)
    }

    @Test
    fun `bold title variant is supported`() {
        val raw = """
            **Agent Result (SUCCESS)**

            All good.

            ### Changes
            * touched file A
            * touched file B
        """.trimIndent()

        val result = StructuredResultParser.parse(raw)

        assertEquals(AgentResultStatus.SUCCESS, result.status)
        assertEquals("All good.", result.summary)
        assertEquals(listOf("touched file A", "touched file B"), result.changes)
    }

    @Test
    fun `numbered list markers are stripped`() {
        val raw = """
            ## Agent Result (SUCCESS)

            ok

            ### Findings
            1. first finding
            2. second finding
        """.trimIndent()

        val result = StructuredResultParser.parse(raw)

        assertEquals(listOf("first finding", "second finding"), result.findings)
    }

    @Test
    fun `title without status defaults to success`() {
        val raw = """
            ## Agent Result

            summary only
        """.trimIndent()

        val result = StructuredResultParser.parse(raw)

        assertEquals(AgentResultStatus.SUCCESS, result.status)
        assertEquals("summary only", result.summary)
    }

    @Test
    fun `multi-line summary is preserved`() {
        val raw = """
            ## Agent Result (SUCCESS)

            Line one of summary.
            Line two of summary.

            ### Findings
            - f1
        """.trimIndent()

        val result = StructuredResultParser.parse(raw)

        assertEquals("Line one of summary.\nLine two of summary.", result.summary)
    }

    @Test
    fun `missing sections become empty lists`() {
        val raw = """
            ## Agent Result (SUCCESS)

            Just a summary.
        """.trimIndent()

        val result = StructuredResultParser.parse(raw)

        assertTrue(result.findings.isEmpty())
        assertTrue(result.changes.isEmpty())
        assertTrue(result.risks.isEmpty())
    }

    @Test
    fun `summary section content is merged into summary`() {
        val raw = """
            ## Agent Result (SUCCESS)

            ### Summary
            Summary from its own section.

            ### Findings
            - f1
        """.trimIndent()

        val result = StructuredResultParser.parse(raw)

        assertEquals("Summary from its own section.", result.summary)
        assertEquals(listOf("f1"), result.findings)
    }

    // ---- 自定义输出契约（resultFormat）----

    @Test
    fun `custom contract sections are parsed into sections map`() {
        val sections = listOf("Bugs", "Security", "Suggestions")
        val raw = """
            ## Agent Result (SUCCESS)

            Reviewed the codebase.

            ### Bugs
            - [P0] null pointer in login
            - [P1] memory leak in cache

            ### Security
            - hardcoded api key

            ### Suggestions
            - add unit tests
        """.trimIndent()

        val result = StructuredResultParser.parse(raw, sections = sections)

        assertEquals("Reviewed the codebase.", result.summary)
        assertEquals(
            mapOf(
                "Bugs" to listOf("[P0] null pointer in login", "[P1] memory leak in cache"),
                "Security" to listOf("hardcoded api key"),
                "Suggestions" to listOf("add unit tests"),
            ),
            result.sections,
        )
        // 自定义契约没有 Findings/Changes/Risks → 兼容字段为空
        assertTrue(result.findings.isEmpty())
        assertTrue(result.changes.isEmpty())
        assertTrue(result.risks.isEmpty())
    }

    @Test
    fun `custom contract with default names still fills legacy fields`() {
        val sections = listOf("Findings", "Suggestions")
        val raw = """
            ## Agent Result (SUCCESS)

            ok

            ### Findings
            - found a bug

            ### Suggestions
            - refactor later
        """.trimIndent()

        val result = StructuredResultParser.parse(raw, sections = sections)

        assertEquals(listOf("found a bug"), result.findings)
        assertEquals(listOf("refactor later"), result.sections["Suggestions"])
    }

    @Test
    fun `forRole with null resultFormat returns default contract`() {
        val contract = SubagentResultContract.forRole(null)

        assertEquals(listOf("Findings", "Changes", "Risks"), contract.sections)
        assertTrue(contract.systemPrompt.contains("### Findings"))
        assertTrue(contract.systemPrompt.contains("### Changes"))
        assertTrue(contract.systemPrompt.contains("### Risks"))
        assertEquals(SubagentResultContract.PREFILL, contract.prefill)
    }

    @Test
    fun `forRole with custom resultFormat generates matching contract`() {
        val contract = SubagentResultContract.forRole("Summary, Bugs, Security, Suggestions")

        assertEquals(listOf("Summary", "Bugs", "Security", "Suggestions"), contract.sections)
        assertTrue(contract.systemPrompt.contains("### Bugs"))
        assertTrue(contract.systemPrompt.contains("### Security"))
        assertTrue(contract.systemPrompt.contains("### Suggestions"))
        assertTrue(!contract.systemPrompt.contains("### Findings"))
    }

    @Test
    fun `forRole with blank resultFormat falls back to default`() {
        val contract = SubagentResultContract.forRole("  ,  ")

        assertEquals(listOf("Findings", "Changes", "Risks"), contract.sections)
    }

    @Test
    fun `forRole with raw marker returns raw contract`() {
        val contract = SubagentResultContract.forRole("raw")

        assertTrue(contract.raw)
        assertEquals(emptyList<String>(), contract.sections)
        assertEquals("", contract.prefill)
        // raw 模式注入防御性纯文本指令（压制模型自发结构化）
        assertTrue(contract.systemPrompt.contains("PLAIN TEXT"))
        assertTrue(contract.systemPrompt.contains("No result block"))
    }

    @Test
    fun `forRole with Raw case-insensitive returns raw contract`() {
        val contract = SubagentResultContract.forRole("Raw")

        assertTrue(contract.raw)
    }

    @Test
    fun `raw mode parse returns content untouched`() {
        val raw = "这是一段纯文本内容。\n\n第二段：无需任何结构化包装。\n```\n代码块也原样保留\n```"
        val result = StructuredResultParser.parse(raw, rawMode = true)

        assertTrue(result.raw)
        assertEquals(raw, result.deliverable)
        assertEquals(raw, result.summary)
        assertTrue(result.findings.isEmpty())
        assertTrue(result.sections.isEmpty())
    }

    @Test
    fun `raw mode toText returns content without result block wrapper`() {
        val raw = "纯文本内容，包含 ## Agent Result (SUCCESS) 字样也不被解析"
        val result = StructuredResultParser.parse(raw, rawMode = true)

        val text = result.toText

        // raw 模式：无 `## Agent Result` 包装（内容里的字样是原文，不是解析产物）
        assertEquals(raw, text)
    }

    // ---- 空结果判定 ----

    @Test
    fun `empty result detection`() {
        assertTrue(AgentResult().isEmptyResult)
        assertTrue(AgentResult(summary = "").isEmptyResult)
        assertFalse(AgentResult(summary = "x").isEmptyResult)
        assertFalse(AgentResult(deliverable = "content").isEmptyResult)
        assertFalse(AgentResult(sections = mapOf("Bugs" to listOf("b1"))).isEmptyResult)
        assertFalse(AgentResult(findings = listOf("f1")).isEmptyResult)
    }

    // ---- 效率指南 ----

    @Test
    fun `efficiency guidelines cover iteration and empty-hand issues`() {
        val guidelines = SubagentResultContract.EFFICIENCY_GUIDELINES

        assertTrue(guidelines.contains("ONE pass"))
        assertTrue(guidelines.contains("Never return empty-handed"))
        assertTrue(guidelines.contains("WRITE IT FIRST"))
        assertTrue(guidelines.contains("skip self-verification"))
    }

    @Test
    fun `contract prompt requires deliverable first then result block`() {
        val contract = SubagentResultContract.forRole(null)

        assertTrue(contract.systemPrompt.contains("FULL deliverable"))
        assertTrue(contract.systemPrompt.contains("FIRST"))
        assertTrue(contract.systemPrompt.contains("only the block is parsed"))
        assertTrue(contract.systemPrompt.contains("never replace the deliverable"))
    }

    // ---- toText round-trip ----

    @Test
    fun `toText with sections keeps custom section names`() {
        val result = AgentResult(
            status = AgentResultStatus.SUCCESS,
            summary = "Reviewed.",
            sections = mapOf(
                "Bugs" to listOf("bug1", "bug2"),
                "Suggestions" to listOf("add tests"),
            ),
        )

        val text = result.toText

        assertTrue(text.contains("## Agent Result (SUCCESS)"))
        assertTrue(text.contains("Reviewed."))
        assertTrue(text.contains("### Bugs"))
        assertTrue(text.contains("- bug1"))
        assertTrue(text.contains("- bug2"))
        assertTrue(text.contains("### Suggestions"))
        assertTrue(text.contains("- add tests"))
    }

    @Test
    fun `toText without sections falls back to legacy fields`() {
        val result = AgentResult(
            status = AgentResultStatus.SUCCESS,
            summary = "ok",
            findings = listOf("f1"),
            risks = listOf("r1"),
        )

        val text = result.toText

        assertTrue(text.contains("### Findings"))
        assertTrue(text.contains("- f1"))
        assertTrue(text.contains("### Risks"))
        assertTrue(text.contains("- r1"))
        assertTrue(!text.contains("### Changes"))
    }

    @Test
    fun `toText emits deliverable before result block`() {
        val result = AgentResult(
            status = AgentResultStatus.SUCCESS,
            summary = "Wrote the code.",
            deliverable = "```kotlin\nfun hello() = \"world\"\n```",
        )

        val text = result.toText

        // 产出物在结果块之前
        assertTrue(text.indexOf("```kotlin") < text.indexOf("## Agent Result (SUCCESS)"))
        assertTrue(text.contains("fun hello() = \"world\""))
        assertTrue(text.contains("Wrote the code."))
    }

    // ---- 预填充（prefill）----

    @Test
    fun `prefill prefix is stripped and status inherited`() {
        val prefill = SubagentResultContract.PREFILL
        val raw = prefill + "Finished the task successfully.\n\n### Findings\n- f1"

        val result = StructuredResultParser.parse(raw, prefill)

        assertEquals(AgentResultStatus.SUCCESS, result.status)
        assertEquals("Finished the task successfully.", result.summary)
        assertEquals(listOf("f1"), result.findings)
    }

    @Test
    fun `model repeating title after prefill is handled`() {
        val prefill = SubagentResultContract.PREFILL
        val raw = prefill + "some notes\n\n## Agent Result (FAILED)\n\nIt broke.\n\n### Risks\n- data loss"

        val result = StructuredResultParser.parse(raw, prefill)

        assertEquals(AgentResultStatus.FAILED, result.status)
        assertEquals("It broke.", result.summary)
        assertEquals(listOf("data loss"), result.risks)
    }

    // ---- JSON 格式 ----

    @Test
    fun `json object format is parsed`() {
        val raw = """
            {"status":"SUCCESS","summary":"json summary","findings":["f1","f2"],"changes":[],"risks":["r1"]}
        """.trimIndent()

        val result = StructuredResultParser.parse(raw)

        assertEquals(AgentResultStatus.SUCCESS, result.status)
        assertEquals("json summary", result.summary)
        assertEquals(listOf("f1", "f2"), result.findings)
        assertEquals(emptyList<String>(), result.changes)
        assertEquals(listOf("r1"), result.risks)
    }

    @Test
    fun `json with failed status`() {
        val raw = """{"status":"FAILED","summary":"boom"}"""

        val result = StructuredResultParser.parse(raw)

        assertEquals(AgentResultStatus.FAILED, result.status)
        assertEquals("boom", result.summary)
    }

    @Test
    fun `json wrapped in markdown fence is ignored as json and falls back to markdown`() {
        val raw = """
            ```json
            {"status":"SUCCESS","summary":"x"}
            ```
        """.trimIndent()

        val result = StructuredResultParser.parse(raw)

        // 不以 { 开头 → 不进 JSON 分支；无标题 → 整体作为 summary
        assertEquals(AgentResultStatus.SUCCESS, result.status)
        assertTrue(result.summary.contains("\"summary\""))
    }

    // ---- 回退 ----

    @Test
    fun `plain text without contract falls back to full summary`() {
        val raw = "Just some plain text output without any structure."

        val result = StructuredResultParser.parse(raw)

        assertEquals(AgentResultStatus.SUCCESS, result.status)
        assertEquals(raw, result.summary)
        assertEquals(raw, result.rawOutput)
    }

    @Test
    fun `empty output produces empty summary marker`() {
        val result = StructuredResultParser.parse("   ", prefill = SubagentResultContract.PREFILL)

        assertEquals(AgentResultStatus.SUCCESS, result.status)
        assertEquals("(empty output)", result.summary)
    }

    @Test
    fun `content before result block becomes deliverable`() {
        val raw = """
            I searched the web and found several candidates.

            ## Agent Result (SUCCESS)

            Picked model A.

            ### Changes
            - wrote report.md
        """.trimIndent()

        val result = StructuredResultParser.parse(raw)

        assertEquals("Picked model A.", result.summary)
        assertEquals("I searched the web and found several candidates.", result.deliverable)
        assertEquals(listOf("wrote report.md"), result.changes)
    }

    @Test
    fun `writer style output keeps full deliverable`() {
        val raw = """
            ```kotlin
            fun hello() = "world"
            ```

            ## Agent Result (SUCCESS)

            Wrote the hello function.

            ### Changes
            - added hello()
        """.trimIndent()

        val result = StructuredResultParser.parse(raw)

        assertEquals("```kotlin\nfun hello() = \"world\"\n```", result.deliverable)
        assertEquals("Wrote the hello function.", result.summary)
        assertEquals(listOf("added hello()"), result.changes)
    }

    @Test
    fun `no result block means no deliverable (fallback)`() {
        val raw = "Just some plain text without any result block."

        val result = StructuredResultParser.parse(raw)

        assertEquals(raw, result.summary)
        assertEquals("", result.deliverable)
    }
}
