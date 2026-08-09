package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/** 单次读取文本文件的最大字节数（超出截断提示） */
private const val READ_TEXT_LIMIT = 200_000

/** 单次写入文本文件的最大字节数 */
private const val WRITE_TEXT_LIMIT = 500_000

/**
 * 手机文件管理工具：在应用私有目录（filesDir）内管理文件。
 *
 * 安全模型：
 * - 所有路径解析后必须位于 `context.filesDir` 内，禁止越界访问（防路径穿越）
 * - 不申请外部存储权限，AI 只能在应用沙盒目录内读写
 * - 支持动作：list / read / write / delete / mkdir / info
 */
internal fun buildPhoneFileTool(context: Context): Tool = Tool(
    name = "phone_files",
    description = """
        Manage files on the phone inside the app's private storage directory (filesDir).
        Actions:
        - list: list entries of a directory (name, type, size, last modified)
        - read: read a text file (returns raw text, max ${READ_TEXT_LIMIT / 1000}KB)
        - write: write text content to a file (creates parent dirs; max ${WRITE_TEXT_LIMIT / 1000}KB)
        - delete: delete a file or an empty directory
        - mkdir: create a directory (including parents)
        - info: show the app private dir path and device storage (total/free) info
        Paths are relative to the app private directory and may not escape it (no "..").
        Use this to save notes, read AI-generated files, organize app data, etc.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            add("list")
                            add("read")
                            add("write")
                            add("delete")
                            add("mkdir")
                            add("info")
                        }
                    )
                    put("description", "Operation to perform")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Relative path inside the app private dir, e.g. 'notes/idea.txt' or '.' for root")
                })
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "Text content to write (required for write action)")
                })
            },
            required = listOf("action")
        )
    },
    execute = {
        val params = it.jsonObject
        val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
        val path = params["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        when (action) {
            "info" -> {
                val payload = buildJsonObject {
                    put("app_private_dir", context.filesDir.absolutePath)
                    put("app_files_dir", context.filesDir.absolutePath)
                    put("internal_storage", storageInfo(Environment.getDataDirectory().absolutePath))
                    put("external_storage", storageInfo(Environment.getExternalStorageDirectory().absolutePath))
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }

            "list" -> {
                val dir = resolveSafeDir(context, path)
                if (!dir.exists()) error("directory not found: $path")
                if (!dir.isDirectory) error("not a directory: $path")
                val entries = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
                val payload = buildJsonObject {
                    put("path", path.ifBlank { "." })
                    put("absolute_path", dir.absolutePath)
                    put("entries", buildJsonArray {
                        entries.forEach { f ->
                            add(buildJsonObject {
                                put("name", f.name)
                                put("type", if (f.isDirectory) "dir" else "file")
                                put("size", f.length())
                                put("last_modified", f.lastModified())
                            })
                        }
                    })
                    put("entry_count", entries.size)
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }

            "read" -> {
                val file = resolveSafeFile(context, path)
                if (!file.exists()) error("file not found: $path")
                if (file.isDirectory) error("is a directory: $path")
                if (file.length() > READ_TEXT_LIMIT) {
                    error("file too large to read as text: ${file.length()} bytes (limit ${READ_TEXT_LIMIT})")
                }
                val text = file.readText()
                listOf(UIMessagePart.Text(text))
            }

            "write" -> {
                val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required for write")
                if (content.length > WRITE_TEXT_LIMIT) {
                    error("content too large: ${content.length} chars (limit ${WRITE_TEXT_LIMIT})")
                }
                val file = resolveSafeFile(context, path)
                file.parentFile?.mkdirs()
                file.writeText(content)
                val payload = buildJsonObject {
                    put("success", true)
                    put("path", path)
                    put("absolute_path", file.absolutePath)
                    put("size", file.length())
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }

            "delete" -> {
                val file = resolveSafeFile(context, path)
                if (!file.exists()) error("path not found: $path")
                val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
                if (!deleted) error("failed to delete: $path")
                val payload = buildJsonObject {
                    put("success", true)
                    put("path", path)
                    put("deleted", true)
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }

            "mkdir" -> {
                val dir = resolveSafeDir(context, path)
                val created = dir.mkdirs()
                if (!created && !dir.isDirectory) error("failed to create directory: $path")
                val payload = buildJsonObject {
                    put("success", true)
                    put("path", path)
                    put("absolute_path", dir.absolutePath)
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }

            else -> error("unknown action: $action, must be one of [list, read, write, delete, mkdir, info]")
        }
    }
)

/** 解析相对路径为 filesDir 内的 File，拒绝越界（.. 或绝对路径） */
private fun resolveSafeFile(context: Context, path: String): File {
    if (path.isBlank()) error("path is required")
    if (path.startsWith("/") || path.contains("..")) {
        error("path may not be absolute or contain '..': $path")
    }
    val file = File(context.filesDir, path)
    if (!file.canonicalPath.startsWith(context.filesDir.canonicalPath)) {
        error("path escapes the app private directory: $path")
    }
    return file
}

/** 解析相对路径为 filesDir 内的目录（'.' 表示根目录） */
private fun resolveSafeDir(context: Context, path: String): File {
    val normalized = path.ifBlank { "." }
    return resolveSafeFile(context, normalized)
}

/** 返回指定路径的存储信息（总/可用字节） */
private fun storageInfo(path: String): String {
    return try {
        val stat = StatFs(path)
        val total = stat.totalBytes
        val free = stat.availableBytes
        "$path (total=${formatBytes(total)}, free=${formatBytes(free)})"
    } catch (e: Exception) {
        "$path (unavailable)"
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.2f MB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.2f KB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}
