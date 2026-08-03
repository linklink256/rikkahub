package me.rerere.rikkahub.data.files

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * tools: workspace_read_file, workspace_shell, search
 * model: gemini-flash        # 可选，不填则继承主 agent 模型
 * maxIterations: 8           # 可选，默认 10
 * temperature: 0.2           # 可选
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
        private const val DEFAULT_MAX_ITERATIONS = 10
        const val DEFAULT_GROUP = "default"
    }

    fun getAgentsDir(): File {
        val dir = context.filesDir.resolve(FileFolders.AGENTS)
        if (!dir.exists()) dir.mkdirs()
        return dir
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
                val pruned = assistant.enabledSubagents.filterTo(LinkedHashSet()) { it in existing }
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
            SubagentMetadata(
                name = name,
                description = description,
                group = frontmatter["group"]?.takeIf { it.isNotBlank() } ?: DEFAULT_GROUP,
                tools = frontmatter["tools"]
                    ?.split(",", " ")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?: emptyList(),
                model = frontmatter["model"]?.takeIf { it.isNotBlank() },
                maxIterations = frontmatter["maxIterations"]?.toIntOrNull() ?: DEFAULT_MAX_ITERATIONS,
                temperature = frontmatter["temperature"]?.toFloatOrNull(),
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
    val group: String = DEFAULT_GROUP,
    val tools: List<String> = emptyList(),
    val model: String? = null,
    val maxIterations: Int = 10,
    val temperature: Float? = null,
    val agentDir: File,
) {
    val agentFile: File get() = agentDir.resolve("AGENT.md")
}
