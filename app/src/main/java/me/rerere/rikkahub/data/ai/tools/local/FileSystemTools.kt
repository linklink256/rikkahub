package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/** 单次读取文本文件的最大字节数（超出拒绝） */
private const val READ_TEXT_LIMIT = 500_000L

/** 搜索的最大结果数 */
private const val SEARCH_MAX_RESULTS = 100

/**
 * 手机文件系统工具集：基于绝对路径操作设备上的文件与目录
 * （内部存储、外部存储、SD 卡等，需要"所有文件访问权限"）。
 *
 * 权限模型（参照 rikkahubx）：
 * - Android 11+（R）：需要用户手动授予 MANAGE_EXTERNAL_STORAGE（所有文件访问权限）
 * - Android 10（Q）：requestLegacyExternalStorage + WRITE_EXTERNAL_STORAGE
 * - Android 9 及以下：READ/WRITE_EXTERNAL_STORAGE 运行时权限
 * 权限不足时返回 {success:false, needPermission:true, error:...} 并附带授权指引。
 */
internal fun buildFileSystemTools(context: Context): List<Tool> = listOf(
    fileReadTool(context),
    fileWriteTool(context),
    listDirectoryTool(context),
    fileInfoTool(context),
    fileDeleteTool(context),
    fileMoveTool(context),
    createDirectoryTool(context),
    copyFileTool(context),
    searchFilesTool(context),
    systemInfoTool(context),
)

/** 权限提示：告知用户如何授予"所有文件访问权限" */
private fun permissionHint(): String = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
        "请授予「所有文件访问权限」: 设置 -> 应用 -> RikkaHub -> 权限 -> 文件和媒体 -> 允许管理所有文件"
    }
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
        "请授予存储权限: 设置 -> 应用 -> RikkaHub -> 权限 -> 存储"
    }
    else -> {
        "请在系统设置中授予存储权限"
    }
}

/** 检查当前是否具备全文件访问能力 */
private fun hasAllFilesAccess(context: Context): Boolean = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> true // requestLegacyExternalStorage
    else -> true
}

/** 构造权限失败响应 */
private fun permissionError(path: String, action: String): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        buildJsonObject {
            put("success", JsonPrimitive(false))
            put("needPermission", JsonPrimitive(true))
            put("error", JsonPrimitive("$action 需要存储权限: $path. ${permissionHint()}"))
        }.toString()
    )
)

private fun errorJson(message: String): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        buildJsonObject {
            put("success", JsonPrimitive(false))
            put("error", JsonPrimitive(message))
        }.toString()
    )
)

// ---- 读文件 ----

private fun fileReadTool(context: Context): Tool = Tool(
    name = "read_file",
    description = "Read the content of a file from the device by absolute path. Supports app internal storage, external storage and SD card. Returns file content as text (max 500KB).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "The absolute path of the file to read, e.g. /sdcard/Documents/example.txt")
                })
                put("encoding", buildJsonObject {
                    put("type", "string")
                    put("description", "Character encoding (default UTF-8). Options: UTF-8, GBK, ISO-8859-1")
                })
            },
            required = listOf("path")
        )
    },
    execute = { args ->
        val path = args.jsonObject["path"]?.jsonPrimitive?.contentOrNull
        if (path.isNullOrBlank()) return@Tool errorJson("Path is required")
        if (!hasAllFilesAccess(context)) return@Tool permissionError(path, "read_file")
        val file = File(path)
        if (!file.exists()) return@Tool errorJson("File not found: $path")
        if (!file.isFile) return@Tool errorJson("Path is not a file: $path")
        if (!file.canRead()) return@Tool permissionError(path, "read_file")
        if (file.length() > READ_TEXT_LIMIT) {
            return@Tool errorJson("File too large to read as text: ${file.length()} bytes (limit $READ_TEXT_LIMIT)")
        }
        val encoding = args.jsonObject["encoding"]?.jsonPrimitive?.contentOrNull ?: "UTF-8"
        runCatching {
            val content = file.readText(charset(encoding))
            listOf(UIMessagePart.Text(content))
        }.getOrElse {
            errorJson("Failed to read file: ${it.message}")
        }
    }
)

// ---- 写文件 ----

private fun fileWriteTool(context: Context): Tool = Tool(
    name = "write_file",
    description = "Write content to a file on the device by absolute path. Can create new files or overwrite existing ones. Creates parent directories automatically.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "The absolute path of the file to write, e.g. /sdcard/Documents/example.txt")
                })
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "The content to write")
                })
                put("append", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, append to the file instead of overwriting (default false)")
                })
                put("encoding", buildJsonObject {
                    put("type", "string")
                    put("description", "Character encoding (default UTF-8). Options: UTF-8, GBK, ISO-8859-1")
                })
            },
            required = listOf("path", "content")
        )
    },
    execute = { args ->
        val path = args.jsonObject["path"]?.jsonPrimitive?.contentOrNull
        val content = args.jsonObject["content"]?.jsonPrimitive?.contentOrNull
        if (path.isNullOrBlank()) return@Tool errorJson("Path is required")
        if (content == null) return@Tool errorJson("Content is required")
        if (!hasAllFilesAccess(context)) return@Tool permissionError(path, "write_file")
        val file = File(path)
        if (file.exists() && !file.canWrite()) return@Tool permissionError(path, "write_file")
        val append = args.jsonObject["append"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
        val encoding = args.jsonObject["encoding"]?.jsonPrimitive?.contentOrNull ?: "UTF-8"
        runCatching {
            file.parentFile?.mkdirs()
            if (append) file.appendText(content, charset(encoding)) else file.writeText(content, charset(encoding))
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", JsonPrimitive(true))
                        put("path", JsonPrimitive(file.absolutePath))
                        put("size", JsonPrimitive(file.length()))
                    }.toString()
                )
            )
        }.getOrElse {
            errorJson("Failed to write file: ${it.message}")
        }
    }
)

// ---- 列目录 ----

private fun listDirectoryTool(context: Context): Tool = Tool(
    name = "list_directory",
    description = "List files and directories in a directory by absolute path. Returns name, path, type, size and last modified for each entry.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "The absolute path of the directory to list, e.g. /sdcard/Documents")
                })
                put("recursive", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, list recursively (default false)")
                })
                put("includeHidden", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, include hidden files starting with . (default false)")
                })
            },
            required = listOf("path")
        )
    },
    execute = { args ->
        val path = args.jsonObject["path"]?.jsonPrimitive?.contentOrNull
        if (path.isNullOrBlank()) return@Tool errorJson("Path is required")
        if (!hasAllFilesAccess(context)) return@Tool permissionError(path, "list_directory")
        val dir = File(path)
        if (!dir.exists()) return@Tool errorJson("Directory not found: $path")
        if (!dir.isDirectory) return@Tool errorJson("Path is not a directory: $path")
        if (!dir.canRead()) return@Tool permissionError(path, "list_directory")
        val recursive = args.jsonObject["recursive"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
        val includeHidden = args.jsonObject["includeHidden"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false

        fun collect(directory: File, depth: Int = 0): List<JsonPrimitive> {
            val result = mutableListOf<JsonPrimitive>()
            val files = directory.listFiles()?.sortedBy { it.name } ?: return result
            for (file in files) {
                if (!includeHidden && file.name.startsWith(".")) continue
                result.add(
                    JsonPrimitive(
                        buildJsonObject {
                            put("name", JsonPrimitive(file.name))
                            put("path", JsonPrimitive(file.absolutePath))
                            put("type", JsonPrimitive(if (file.isDirectory) "directory" else "file"))
                            put("size", JsonPrimitive(file.length()))
                            put("lastModified", JsonPrimitive(file.lastModified()))
                            put("depth", JsonPrimitive(depth))
                        }.toString()
                    )
                )
                if (recursive && file.isDirectory && file.canRead()) {
                    result.addAll(collect(file, depth + 1))
                }
            }
            return result
        }

        runCatching {
            val items = collect(dir)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", JsonPrimitive(true))
                        put("path", JsonPrimitive(dir.absolutePath))
                        put("count", JsonPrimitive(items.size))
                        put("items", buildJsonArray {
                            items.forEach { add(it) }
                        })
                    }.toString()
                )
            )
        }.getOrElse {
            errorJson("Failed to list directory: ${it.message}")
        }
    }
)

// ---- 文件信息 ----

private fun fileInfoTool(context: Context): Tool = Tool(
    name = "file_info",
    description = "Get detailed information about a file or directory by absolute path: size, permissions, timestamps, isDirectory, isFile, etc.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "The absolute path of the file or directory")
                })
            },
            required = listOf("path")
        )
    },
    execute = { args ->
        val path = args.jsonObject["path"]?.jsonPrimitive?.contentOrNull
        if (path.isNullOrBlank()) return@Tool errorJson("Path is required")
        if (!hasAllFilesAccess(context)) return@Tool permissionError(path, "file_info")
        val file = File(path)
        if (!file.exists()) return@Tool errorJson("Path not found: $path")
        runCatching {
            val children = if (file.isDirectory) (file.listFiles()?.size ?: 0) else null
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", JsonPrimitive(true))
                        put("path", JsonPrimitive(file.absolutePath))
                        put("name", JsonPrimitive(file.name))
                        put("isFile", JsonPrimitive(file.isFile))
                        put("isDirectory", JsonPrimitive(file.isDirectory))
                        put("isHidden", JsonPrimitive(file.isHidden))
                        put("size", JsonPrimitive(file.length()))
                        put("lastModified", JsonPrimitive(file.lastModified()))
                        put("canRead", JsonPrimitive(file.canRead()))
                        put("canWrite", JsonPrimitive(file.canWrite()))
                        put("canExecute", JsonPrimitive(file.canExecute()))
                        put("parent", JsonPrimitive(file.parent))
                        if (children != null) put("childCount", JsonPrimitive(children))
                    }.toString()
                )
            )
        }.getOrElse {
            errorJson("Failed to get file info: ${it.message}")
        }
    }
)

// ---- 删除 ----

private fun fileDeleteTool(context: Context): Tool = Tool(
    name = "delete_file",
    description = "Delete a file or empty directory by absolute path. Use recursive=true to delete non-empty directories (use with caution).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "The absolute path of the file or directory to delete")
                })
                put("recursive", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, delete directory and all contents recursively (default false, use with caution!)")
                })
            },
            required = listOf("path")
        )
    },
    execute = { args ->
        val path = args.jsonObject["path"]?.jsonPrimitive?.contentOrNull
        if (path.isNullOrBlank()) return@Tool errorJson("Path is required")
        if (!hasAllFilesAccess(context)) return@Tool permissionError(path, "delete_file")
        val file = File(path)
        if (!file.exists()) return@Tool errorJson("Path not found: $path")
        if (!file.canWrite()) return@Tool permissionError(path, "delete_file")
        val recursive = args.jsonObject["recursive"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
        runCatching {
            val deleted = if (recursive && file.isDirectory) file.deleteRecursively() else file.delete()
            if (deleted) {
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("success", JsonPrimitive(true))
                            put("message", JsonPrimitive("Deleted: $path"))
                        }.toString()
                    )
                )
            } else {
                errorJson("Failed to delete: $path (directory may not be empty, use recursive=true)")
            }
        }.getOrElse {
            errorJson("Failed to delete: ${it.message}")
        }
    }
)

// ---- 移动/重命名 ----

private fun fileMoveTool(context: Context): Tool = Tool(
    name = "move_file",
    description = "Move or rename a file or directory by absolute path. Can move between directories or rename in place.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("source", buildJsonObject {
                    put("type", "string")
                    put("description", "The absolute path of the source file or directory")
                })
                put("destination", buildJsonObject {
                    put("type", "string")
                    put("description", "The absolute path of the destination")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, overwrite destination if it exists (default false)")
                })
            },
            required = listOf("source", "destination")
        )
    },
    execute = { args ->
        val source = args.jsonObject["source"]?.jsonPrimitive?.contentOrNull
        val destination = args.jsonObject["destination"]?.jsonPrimitive?.contentOrNull
        if (source.isNullOrBlank()) return@Tool errorJson("Source path is required")
        if (destination.isNullOrBlank()) return@Tool errorJson("Destination path is required")
        if (!hasAllFilesAccess(context)) return@Tool permissionError(source, "move_file")
        val sourceFile = File(source)
        val destFile = File(destination)
        if (!sourceFile.exists()) return@Tool errorJson("Source not found: $source")
        val overwrite = args.jsonObject["overwrite"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
        if (destFile.exists() && !overwrite) {
            return@Tool errorJson("Destination already exists: $destination (use overwrite=true to replace)")
        }
        runCatching {
            destFile.parentFile?.mkdirs()
            if (destFile.exists() && overwrite) {
                if (destFile.isDirectory) destFile.deleteRecursively() else destFile.delete()
            }
            val ok = sourceFile.renameTo(destFile)
            if (ok) {
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("success", JsonPrimitive(true))
                            put("source", JsonPrimitive(source))
                            put("destination", JsonPrimitive(destFile.absolutePath))
                        }.toString()
                    )
                )
            } else {
                errorJson("Failed to move/rename: $source -> $destination")
            }
        }.getOrElse {
            errorJson("Failed to move: ${it.message}")
        }
    }
)

// ---- 创建目录 ----

private fun createDirectoryTool(context: Context): Tool = Tool(
    name = "create_directory",
    description = "Create a new directory by absolute path. Creates nested parent directories automatically.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "The absolute path of the directory to create")
                })
            },
            required = listOf("path")
        )
    },
    execute = { args ->
        val path = args.jsonObject["path"]?.jsonPrimitive?.contentOrNull
        if (path.isNullOrBlank()) return@Tool errorJson("Path is required")
        if (!hasAllFilesAccess(context)) return@Tool permissionError(path, "create_directory")
        val dir = File(path)
        runCatching {
            val created = dir.mkdirs()
            if (created || dir.isDirectory) {
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("success", JsonPrimitive(true))
                            put("path", JsonPrimitive(dir.absolutePath))
                        }.toString()
                    )
                )
            } else {
                errorJson("Failed to create directory: $path")
            }
        }.getOrElse {
            errorJson("Failed to create directory: ${it.message}")
        }
    }
)

// ---- 复制 ----

private fun copyFileTool(context: Context): Tool = Tool(
    name = "copy_file",
    description = "Copy a file or directory to a new location by absolute path. Can recursively copy directory trees.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("source", buildJsonObject {
                    put("type", "string")
                    put("description", "The absolute path of the source file or directory")
                })
                put("destination", buildJsonObject {
                    put("type", "string")
                    put("description", "The absolute path of the destination")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, overwrite destination if it exists (default false)")
                })
            },
            required = listOf("source", "destination")
        )
    },
    execute = { args ->
        val source = args.jsonObject["source"]?.jsonPrimitive?.contentOrNull
        val destination = args.jsonObject["destination"]?.jsonPrimitive?.contentOrNull
        if (source.isNullOrBlank()) return@Tool errorJson("Source path is required")
        if (destination.isNullOrBlank()) return@Tool errorJson("Destination path is required")
        if (!hasAllFilesAccess(context)) return@Tool permissionError(source, "copy_file")
        val sourceFile = File(source)
        val destFile = File(destination)
        if (!sourceFile.exists()) return@Tool errorJson("Source not found: $source")
        if (!sourceFile.canRead()) return@Tool permissionError(source, "copy_file")
        val overwrite = args.jsonObject["overwrite"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
        if (destFile.exists() && !overwrite) {
            return@Tool errorJson("Destination already exists: $destination (use overwrite=true to replace)")
        }
        runCatching {
            destFile.parentFile?.mkdirs()
            if (destFile.exists() && overwrite) {
                if (destFile.isDirectory) destFile.deleteRecursively() else destFile.delete()
            }
            if (sourceFile.isDirectory) {
                sourceFile.copyRecursively(destFile, overwrite)
            } else {
                sourceFile.copyTo(destFile, overwrite)
            }
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", JsonPrimitive(true))
                        put("source", JsonPrimitive(source))
                        put("destination", JsonPrimitive(destFile.absolutePath))
                        put("size", JsonPrimitive(destFile.length()))
                    }.toString()
                )
            )
        }.getOrElse {
            errorJson("Failed to copy: ${it.message}")
        }
    }
)

// ---- 搜索 ----

private fun searchFilesTool(context: Context): Tool = Tool(
    name = "search_files",
    description = "Search for text patterns in files within a directory by absolute path. Searches file content with keywords or regex, returns matching files with line numbers and context.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "The absolute path of the directory or file to search in")
                })
                put("pattern", buildJsonObject {
                    put("type", "string")
                    put("description", "The text pattern or regex to search for")
                })
                put("useRegex", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, treat pattern as regex (default false)")
                })
                put("caseSensitive", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, search is case-sensitive (default false)")
                })
                put("recursive", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, search subdirectories recursively (default true)")
                })
                put("fileExtensions", buildJsonObject {
                    put("type", "string")
                    put("description", "Comma-separated extensions to filter (e.g. 'txt,log,md'). Empty = all files")
                })
                put("maxResults", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum matching files to return (default 50, max $SEARCH_MAX_RESULTS)")
                })
            },
            required = listOf("path", "pattern")
        )
    },
    execute = { args ->
        val path = args.jsonObject["path"]?.jsonPrimitive?.contentOrNull
        val pattern = args.jsonObject["pattern"]?.jsonPrimitive?.contentOrNull
        if (path.isNullOrBlank()) return@Tool errorJson("Path is required")
        if (pattern.isNullOrBlank()) return@Tool errorJson("Pattern is required")
        if (!hasAllFilesAccess(context)) return@Tool permissionError(path, "search_files")
        val searchRoot = File(path)
        if (!searchRoot.exists()) return@Tool errorJson("Path not found: $path")
        if (!searchRoot.canRead()) return@Tool permissionError(path, "search_files")

        val useRegex = args.jsonObject["useRegex"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
        val caseSensitive = args.jsonObject["caseSensitive"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
        val recursive = args.jsonObject["recursive"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: true
        val fileExtensions = args.jsonObject["fileExtensions"]?.jsonPrimitive?.contentOrNull
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val maxResults = (args.jsonObject["maxResults"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 50)
            .coerceIn(1, SEARCH_MAX_RESULTS)

        runCatching {
            val searchRegex = if (useRegex) {
                if (caseSensitive) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
            } else {
                val escaped = Regex.escape(pattern)
                if (caseSensitive) Regex(escaped) else Regex(escaped, RegexOption.IGNORE_CASE)
            }

            val matches = mutableListOf<JsonPrimitive>()
            fun scan(file: File) {
                if (matches.size >= maxResults) return
                if (file.isFile && file.canRead()) {
                    if (fileExtensions.isNotEmpty() && fileExtensions.none { file.extension.equals(it, ignoreCase = true) }) {
                        return
                    }
                    if (file.length() > READ_TEXT_LIMIT) return
                    runCatching {
                        file.readLines().forEachIndexed { index, line ->
                            if (searchRegex.containsMatchIn(line)) {
                                if (matches.size >= maxResults) return
                                matches.add(
                                    JsonPrimitive(
                                        buildJsonObject {
                                            put("file", JsonPrimitive(file.absolutePath))
                                            put("line", JsonPrimitive(index + 1))
                                            put("content", JsonPrimitive(line.trim().take(300)))
                                        }.toString()
                                    )
                                )
                            }
                        }
                    }
                } else if (file.isDirectory && recursive) {
                    file.listFiles()?.forEach { scan(it) }
                }
            }

            if (searchRoot.isDirectory) {
                searchRoot.listFiles()?.forEach { scan(it) }
            } else {
                scan(searchRoot)
            }

            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", JsonPrimitive(true))
                        put("count", JsonPrimitive(matches.size))
                        put("matches", buildJsonArray {
                            matches.forEach { add(it) }
                        })
                    }.toString()
                )
            )
        }.getOrElse {
            errorJson("Failed to search: ${it.message}")
        }
    }
)

// ---- 系统信息 ----

private fun systemInfoTool(context: Context): Tool = Tool(
    name = "get_system_info",
    description = "Get device and system information: device model, Android version, internal/external storage space, and app private dir path.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("infoType", buildJsonObject {
                    put("type", "string")
                    put("description", "'all' (default), 'device', 'storage' or 'app'")
                })
            }
        )
    },
    execute = { args ->
        val infoType = args.jsonObject["infoType"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "all"
        runCatching {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", JsonPrimitive(true))
                        if (infoType == "all" || infoType == "device") {
                            put("device", buildJsonObject {
                                put("manufacturer", JsonPrimitive(Build.MANUFACTURER))
                                put("model", JsonPrimitive(Build.MODEL))
                                put("brand", JsonPrimitive(Build.BRAND))
                                put("device", JsonPrimitive(Build.DEVICE))
                                put("androidVersion", JsonPrimitive(Build.VERSION.RELEASE))
                                put("sdkInt", JsonPrimitive(Build.VERSION.SDK_INT))
                            })
                        }
                        if (infoType == "all" || infoType == "storage") {
                            put("storage", buildJsonObject {
                                val internal = StatFs(Environment.getDataDirectory().path)
                                put("internal", buildJsonObject {
                                    put("totalBytes", JsonPrimitive(internal.totalBytes))
                                    put("availableBytes", JsonPrimitive(internal.availableBytes))
                                })
                                if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                                    val external = StatFs(Environment.getExternalStorageDirectory().path)
                                    put("external", buildJsonObject {
                                        put("totalBytes", JsonPrimitive(external.totalBytes))
                                        put("availableBytes", JsonPrimitive(external.availableBytes))
                                    })
                                }
                                put("allFilesAccess", JsonPrimitive(hasAllFilesAccess(context)))
                            })
                        }
                        if (infoType == "all" || infoType == "app") {
                            put("app", buildJsonObject {
                                put("privateDir", JsonPrimitive(context.filesDir.absolutePath))
                                put("cacheDir", JsonPrimitive(context.cacheDir.absolutePath))
                                put("externalFilesDir", JsonPrimitive(context.getExternalFilesDir(null)?.absolutePath))
                            })
                        }
                    }.toString()
                )
            )
        }.getOrElse {
            errorJson("Failed to get system info: ${it.message}")
        }
    }
)
