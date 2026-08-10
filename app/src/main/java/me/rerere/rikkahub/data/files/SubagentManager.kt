package me.rerere.rikkahub.data.files

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * 子代理（Subagent）角色定义管理器。
 *
 * 每个子代理角色对应 `filesDir/agents/<name>/AGENT.md` 一个文件：
 *
 * ```
 * ---
 * name: scout
 * description: 只读调研，压缩调查结果
 * group: research            # 可选，分组名，用于选择界面按组全选/部分选择；不填归入默认组
 * tools: workspace_read_file, workspace_shell, search   # 可选；tools: none = 禁用全部；未声明 = 继承全部工具池
 * model: openai:gpt-4o       # 可选，支持 '供应商:模型ID' / '供应商/模型ID' / 裸模型ID；不填则继承主 agent 模型
 * reasoningLevel: high       # 可选，off/auto/low/medium/high/xhigh/max，默认 off（也兼容 reasoning 键）
 * resultFormat: Summary, Findings, Risks   # 可选，自定义输出契约段落名（逗号分隔），默认 Summary/Findings/Changes/Risks
 * maxSteps: 30               # 可选，最大工具调用轮数（防空耗循环），默认 30
 * stepTimeout: 120           # 可选，单步工具执行超时秒数，默认 120
 * ---
 * <角色 system prompt 正文>
 * ```
 *
 * 复用 [SkillFrontmatterParser] 与 [SkillManager] 的原子写入模式。
 */
class SubagentManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "SubagentManager"
        const val DEFAULT_GROUP = "default"

        /**
         * 全部禁用哨兵：`enabledSubagents` 含此值时表示"全部禁用"。
         *
         * 空集语义仍为"全部启用"（向后兼容）；用户主动取消全部勾选时，
         * UI 写入此哨兵而非空集，避免"取消全部后又变回全部启用"。
         */
        const val SUBAGENTS_DISABLED_MARKER = "__subagents_disabled__"

        /** `tools: none` 特殊值：显式禁用全部工具（区别于未声明 = 继承全部工具池） */
        const val TOOLS_NONE_MARKER = "none"
    }

    fun getAgentsDir(): File {
        val dir = context.filesDir.resolve(FileFolders.AGENTS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 读取 `_groups/<group>.md` 中的小组描述 → Map<组名, 描述>。
     *
     * 小组描述文件为可选载体，frontmatter 仅需 `name` + `description`（复用 [SkillFrontmatterParser]）。
     * 无 `_groups` 目录、目录为空或解析失败时返回 emptyMap；单个坏文件跳过，不影响其余小组。
     */
    fun listGroupDescriptions(): Map<String, String> {
        val groupsDir = getAgentsDir().resolve("_groups")
        if (!groupsDir.isDirectory) return emptyMap()
        return groupsDir.listFiles()
            ?.filter { it.isFile && it.extension == "md" }
            ?.mapNotNull { file ->
                runCatching {
                    val frontmatter = SkillFrontmatterParser.parse(file.readText())
                    val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: return@runCatching null
                    val description = frontmatter["description"]?.takeIf { it.isNotBlank() } ?: return@runCatching null
                    name to description
                }.getOrElse {
                    Log.w(TAG, "listGroupDescriptions: Failed to parse ${file.absolutePath}", it)
                    null
                }
            }
            ?.toMap()
            ?: emptyMap()
    }

    fun listSubagents(): List<SubagentMetadata> {
        val agentsDir = getAgentsDir()
        return agentsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val agentFile = dir.resolve("AGENT.md")
                if (!agentFile.exists()) return@mapNotNull null
                parseAgentFile(agentFile, dir)
            }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun getSubagentDir(name: String): File? = resolveAgentDir(name)

    fun readSubagentContent(name: String): String? {
        val agentFile = resolveAgentDir(name)?.resolve("AGENT.md") ?: return null
        if (!agentFile.exists()) return null
        return agentFile.readText()
    }

    fun readSubagentBody(name: String): String? {
        val content = readSubagentContent(name) ?: return null
        return SkillFrontmatterParser.extractBody(content)
    }

    fun saveSubagent(name: String, content: String): SubagentMetadata? {
        val agentsDir = getAgentsDir()
        val targetDir = resolveAgentDir(name) ?: return null
        val stagingDir = createTempAgentDir(agentsDir, name, "staging") ?: return null
        var backupDir: File? = null

        try {
            val target = stagingDir.resolve("AGENT.md")
            target.parentFile?.mkdirs()
            target.writeText(content)

            if (targetDir.exists()) {
                backupDir = createTempAgentDir(agentsDir, name, "backup") ?: return null
                if (!targetDir.renameTo(backupDir)) return null
            }

            if (!stagingDir.renameTo(targetDir)) {
                if (backupDir != null && !targetDir.exists()) {
                    backupDir.renameTo(targetDir)
                }
                return null
            }

            backupDir?.deleteRecursively()
            return parseAgentFile(targetDir.resolve("AGENT.md"), targetDir)
        } catch (e: Exception) {
            Log.w(TAG, "saveSubagent: Failed to save $name", e)
            if (backupDir != null && !targetDir.exists()) {
                backupDir.renameTo(targetDir)
            }
            return null
        } finally {
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
            if (backupDir?.exists() == true && targetDir.exists()) {
                backupDir.deleteRecursively()
            }
        }
    }

    suspend fun deleteSubagent(name: String): Boolean = withContext(Dispatchers.IO) {
        val agentDir = resolveAgentDir(name) ?: return@withContext false
        val deleted = agentDir.deleteRecursively()
        if (deleted) {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map { assistant ->
                        if (assistant.enabledSubagents.contains(name)) {
                            assistant.copy(enabledSubagents = assistant.enabledSubagents - name)
                        } else {
                            assistant
                        }
                    }
                )
            }
        }
        deleted
    }

    suspend fun pruneOrphanedEnabledSubagents(): List<SubagentMetadata> = withContext(Dispatchers.IO) {
        val subagents = listSubagents()
        val existing = subagents.mapTo(HashSet()) { it.name }
        settingsStore.update { settings ->
            var changed = false
            val newAssistants = settings.assistants.map { assistant ->
                // 哨兵值（全部禁用标记）不属于任何真实角色，必须保留，不能被当孤儿清理
                val pruned = assistant.enabledSubagents.filterTo(LinkedHashSet()) {
                    it in existing || it == SUBAGENTS_DISABLED_MARKER
                }
                if (pruned.size != assistant.enabledSubagents.size) {
                    changed = true
                    assistant.copy(enabledSubagents = pruned)
                } else {
                    assistant
                }
            }
            if (changed) settings.copy(assistants = newAssistants) else settings
        }
        subagents
    }

    private fun resolveAgentDir(name: String): File? {
        if (name.isBlank() || name.contains('/') || name.contains('\\') || name == "." || name == "..") {
            return null
        }
        return getAgentsDir().resolve(name)
    }

    /**
     * 解析 frontmatter 中的 `reasoningLevel` 字段（兼容 `reasoning` 别名键）。
     *
     * 支持 off / auto / low / medium / high / xhigh / max（大小写不敏感），
     * 无法识别或留空时返回 null（由运行器回退到默认 off）。
     */
    private fun parseReasoningLevel(raw: String?): ReasoningLevel? {
        if (raw.isNullOrBlank()) return null
        return ReasoningLevel.entries.firstOrNull {
            it.name.equals(raw.trim(), ignoreCase = true)
        }
    }

    private fun createTempAgentDir(agentsRoot: File, name: String, suffix: String): File? {
        repeat(100) { attempt ->
            val candidate = agentsRoot.resolve(".$name.$suffix.$attempt.tmp")
            if (!candidate.exists() && candidate.mkdirs()) {
                return candidate
            }
        }
        return null
    }

    private fun parseAgentFile(agentFile: File, agentDir: File): SubagentMetadata? {
        return runCatching {
            val content = agentFile.readText()
            val frontmatter = SkillFrontmatterParser.parse(content)
            val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: return null
            val description = frontmatter["description"]?.takeIf { it.isNotBlank() } ?: return null
            // tools 三态：
            // - 未声明 → 空列表 + toolsDisabled=false（继承全部工具池）
            // - `tools: none` → 空列表 + toolsDisabled=true（显式禁用全部工具）
            // - `tools: a, b` → 白名单 + toolsDisabled=false
            val toolsRaw = frontmatter["tools"]?.trim().orEmpty()
            val toolsDisabled = toolsRaw.equals(TOOLS_NONE_MARKER, ignoreCase = true)
            SubagentMetadata(
                name = name,
                description = description,
                group = frontmatter["group"]?.takeIf { it.isNotBlank() } ?: DEFAULT_GROUP,
                tools = if (toolsDisabled) {
                    emptyList()
                } else {
                    frontmatter["tools"]
                        ?.split(",", " ")
                        ?.map { it.trim() }
                        ?.filter { it.isNotBlank() }
                        ?: emptyList()
                },
                toolsDisabled = toolsDisabled,
                model = frontmatter["model"]?.takeIf { it.isNotBlank() },
                reasoningLevel = parseReasoningLevel(
                    frontmatter["reasoningLevel"] ?: frontmatter["reasoning"]
                ),
                resultFormat = frontmatter["resultFormat"]?.takeIf { it.isNotBlank() },
                maxSteps = frontmatter["maxSteps"]?.trim()?.toIntOrNull()
                    ?.takeIf { it > 0 },
                stepTimeoutMillis = frontmatter["stepTimeout"]?.trim()?.toLongOrNull()
                    ?.takeIf { it > 0 }
                    ?.let { it * 1000 },
                agentDir = agentDir,
            )
        }.getOrElse {
            Log.w(TAG, "parseAgentFile: Failed to parse ${agentFile.absolutePath}", it)
            null
        }
    }
}

data class SubagentMetadata(
    val name: String,
    val description: String,
    val group: String = SubagentManager.DEFAULT_GROUP,
    val tools: List<String> = emptyList(),
    val toolsDisabled: Boolean = false, // tools: none → 显式禁用全部工具（区别于未声明继承全部）
    val model: String? = null,
    val reasoningLevel: ReasoningLevel? = null,
    val resultFormat: String? = null,
    val maxSteps: Int? = null,             // 最大工具调用轮数（空耗防护），默认 SubagentRunner 兜底值
    val stepTimeoutMillis: Long? = null,   // 单步工具执行超时（毫秒），默认 SubagentRunner 兜底值
    val agentDir: File,
) {
    val agentFile: File get() = agentDir.resolve("AGENT.md")
}

/** 子代理小组描述（来源 `_groups/<group>.md` 的 frontmatter）。 */
data class SubagentGroup(
    val name: String,
    val description: String,
)

/**
 * 按 `enabled` 白名单过滤子代理角色。
 *
 * - 空集 = 全部启用（不限制，向后兼容现状）
 * - 含 [SubagentManager.SUBAGENTS_DISABLED_MARKER] 哨兵 = 全部禁用（用户主动取消全部勾选）
 * - 其余 = 仅启用集合内名字的角色
 */
fun List<SubagentMetadata>.applyEnabledSubagents(enabled: Set<String>): List<SubagentMetadata> =
    when {
        enabled.isEmpty() -> this
        SubagentManager.SUBAGENTS_DISABLED_MARKER in enabled -> emptyList()
        else -> filter { it.name in enabled }
    }
