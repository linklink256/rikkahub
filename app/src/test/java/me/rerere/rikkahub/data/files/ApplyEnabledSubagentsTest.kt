package me.rerere.rikkahub.data.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class ApplyEnabledSubagentsTest {

    private fun subagent(name: String): SubagentMetadata = SubagentMetadata(
        name = name,
        description = "desc-$name",
        agentDir = File("dummy"),
    )

    private val all = listOf(
        subagent("alpha"),
        subagent("beta"),
        subagent("gamma"),
    )

    @Test
    fun `empty enabled set returns all subagents`() {
        val result = all.applyEnabledSubagents(emptySet())

        assertEquals(all, result)
    }

    @Test
    fun `non-empty enabled set filters to whitelist`() {
        val result = all.applyEnabledSubagents(setOf("alpha", "gamma"))

        assertEquals(listOf(subagent("alpha"), subagent("gamma")), result)
    }

    @Test
    fun `unknown names in enabled set are ignored`() {
        val result = all.applyEnabledSubagents(setOf("alpha", "unknown", "gamma"))

        assertEquals(listOf(subagent("alpha"), subagent("gamma")), result)
    }

    @Test
    fun `result preserves original order`() {
        val result = all.applyEnabledSubagents(setOf("gamma", "alpha"))

        assertEquals(listOf(subagent("alpha"), subagent("gamma")), result)
    }
}
