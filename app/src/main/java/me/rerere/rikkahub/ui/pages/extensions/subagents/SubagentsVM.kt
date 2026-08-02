package me.rerere.rikkahub.ui.pages.extensions.subagents

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SubagentManager
import me.rerere.rikkahub.data.files.SubagentMetadata
import org.json.JSONArray

/** 模型下拉选项：label 用于展示，value 写入 AGENT.md 的 model 字段 */
data class ModelOption(
    val label: String,
    val value: String,
)

/**
 * 子代理可用的工具清单（会话中实际可用的工具取决于 assistant 配置，
 * 例如 workspace 工具需要绑定工作区；未列出的工具可在 AGENT.md 中手动补充）。
 */
internal val KNOWN_SUBAGENT_TOOLS = listOf(
    // 工作区
    "workspace_read_file",
    "workspace_write_file",
    "workspace_edit_file",
    "workspace_shell",
    // 搜索
    "search_web",
    "scrape_web",
    // 对话引用
    "recent_chats",
    "conversation_search",
    // 技能
    "use_skill",
    // 本地工具
    "eval_javascript",
    "time_info",
    "clipboard",
    "tts",
    "ask_user",
    "screen_time",
    "calendar_query",
    "calendar_create",
)

class SubagentsVM(
    private val subagentManager: SubagentManager,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val _subagents = MutableStateFlow<List<SubagentMetadata>>(emptyList())
    val subagents = _subagents.asStateFlow()

    private val _availableModels = MutableStateFlow<List<ModelOption>>(emptyList())
    val availableModels = _availableModels.asStateFlow()

    val availableTools: List<String> = KNOWN_SUBAGENT_TOOLS

    init {
        loadSubagents()
        observeModels()
    }

    private fun loadSubagents() {
        viewModelScope.launch(Dispatchers.IO) {
            _subagents.value = subagentManager.listSubagents()
        }
    }

    /** 从用户已配置的 provider/models 拉取可用模型 */
    private fun observeModels() {
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                _availableModels.value = settings.providers
                    .flatMap { provider ->
                        provider.models.map { model ->
                            ModelOption(
                                label = "${provider.name} / ${model.displayName} (${model.modelId})",
                                value = model.modelId,
                            )
                        }
                    }
                    .distinctBy { it.value }
            }
        }
    }

    fun saveSubagent(name: String, content: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = subagentManager.saveSubagent(name, content)
            _subagents.value = subagentManager.listSubagents()
            withContext(Dispatchers.Main) {
                onResult(result != null)
            }
        }
    }

    fun deleteSubagent(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            subagentManager.deleteSubagent(name)
            _subagents.value = subagentManager.listSubagents()
        }
    }

    fun getAgentsDir() = subagentManager.getAgentsDir()

    fun importSubagentFromFile(context: Context, uri: Uri, onResult: (Boolean, String) -> Unit) {
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: run {
                        withContext(Dispatchers.Main) { onResult(false, "无法读取文件") }
                        return@launch
                    }
                val importedNames = importSubagentMarkdown(bytes)
                _subagents.value = subagentManager.listSubagents()
                withContext(Dispatchers.Main) {
                    onResult(true, importedNames.joinToString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(false, e.message ?: "未知错误") }
            }
        }
    }

    fun importSubagentFromGitHub(repoUrl: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = parseGitHubUrl(repoUrl) ?: run {
                    withContext(Dispatchers.Main) { onResult(false, "无效的 GitHub 仓库链接") }
                    return@launch
                }

                val files = mutableListOf<Pair<String, String>>() // relativePath -> downloadUrl
                val listed = listFilesRecursively(info.owner, info.repo, info.branch, info.path, info.path, files)
                if (!listed) {
                    withContext(Dispatchers.Main) { onResult(false, "读取 GitHub 目录失败") }
                    return@launch
                }

                val agentMdEntry = files.find {
                    it.first.substringAfterLast('/').equals("AGENT.md", ignoreCase = true)
                } ?: run {
                    withContext(Dispatchers.Main) { onResult(false, "目录中未找到 AGENT.md") }
                    return@launch
                }

                val content = downloadText(agentMdEntry.second) ?: run {
                    withContext(Dispatchers.Main) { onResult(false, "下载 AGENT.md 失败，请检查链接或网络") }
                    return@launch
                }

                val frontmatter = SkillFrontmatterParser.parse(content)
                val name = frontmatter["name"]
                if (name.isNullOrBlank()) {
                    withContext(Dispatchers.Main) { onResult(false, "AGENT.md 格式错误：缺少 name 字段") }
                    return@launch
                }

                val saved = subagentManager.saveSubagent(name, content)
                if (saved == null) {
                    withContext(Dispatchers.Main) { onResult(false, "保存失败") }
                    return@launch
                }

                _subagents.value = subagentManager.listSubagents()
                withContext(Dispatchers.Main) { onResult(true, name) }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { onResult(false, e.message ?: "未知错误") }
            }
        }
    }

    private fun importSubagentMarkdown(bytes: ByteArray): List<String> {
        val content = bytes.toString(Charsets.UTF_8)
        val frontmatter = SkillFrontmatterParser.parse(content)
        val name = frontmatter["name"]?.trim()
        if (name.isNullOrBlank()) {
            error("AGENT.md 格式错误：缺少 name 字段")
        }
        if (frontmatter["description"].isNullOrBlank()) {
            error("AGENT.md 格式错误：缺少 description 字段")
        }
        val saved = subagentManager.saveSubagent(name, content) ?: error("保存失败，请检查角色格式")
        return listOf(saved.name)
    }

    private fun listFilesRecursively(
        owner: String,
        repo: String,
        branch: String,
        dirPath: String,
        basePath: String,
        result: MutableList<Pair<String, String>>,
    ): Boolean {
        val apiUrl = "https://api.github.com/repos/$owner/$repo/contents/$dirPath?ref=$branch"
        val json = downloadText(apiUrl) ?: return false
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val type = item.getString("type")
            val itemPath = item.getString("path")
            val relativePath = itemPath.removePrefix("$basePath/").removePrefix(basePath)
            when (type) {
                "file" -> {
                    val downloadUrl = item.optString("download_url").takeIf { it.isNotBlank() }
                        ?: return false
                    result.add(relativePath to downloadUrl)
                }

                "dir" -> {
                    val ok = listFilesRecursively(owner, repo, branch, itemPath, basePath, result)
                    if (!ok) return false
                }
            }
        }
        return true
    }

    private data class GitHubRepoInfo(
        val owner: String,
        val repo: String,
        val branch: String,
        val path: String,
    )

    private fun parseGitHubUrl(url: String): GitHubRepoInfo? {
        val trimmed = url.trim().trimEnd('/')
        val regex = Regex("""https://github\.com/([^/]+)/([^/]+)(?:/tree/([^/]+)(/.*)?)?""")
        val match = regex.matchEntire(trimmed) ?: return null
        val owner = match.groupValues[1]
        val repo = match.groupValues[2]
        val branch = match.groupValues[3].ifBlank { "HEAD" }
        val subPath = match.groupValues[4].trimStart('/')
        return GitHubRepoInfo(owner, repo, branch, subPath)
    }

    private fun downloadText(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            if (connection.responseCode == 200) connection.inputStream.bufferedReader().readText()
            else null
        } finally {
            connection.disconnect()
        }
    }
}
