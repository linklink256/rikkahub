package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.device.ShellClient
import me.rerere.rikkahub.data.device.ShellDeviceConfig
import me.rerere.rikkahub.data.device.SshClient
import me.rerere.rikkahub.data.device.describeConchRequestFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 终端设备管理工具（移植自 Agora 的 ShellToolProvider）
 *
 * 提供：list_shells / execute_shell_command / file_read / file_write / file_glob / file_grep
 * 支持 Conch 协议设备（加密远程 Shell）与 SSH 设备。
 */
class ShellToolProvider(
    private val settingsStore: SettingsStore,
) {
    private suspend fun currentDevices(): List<ShellDeviceConfig> =
        settingsStore.settingsFlow.first().devices

    private suspend fun findDevice(serverName: String?): ShellDeviceConfig? {
        val devices = currentDevices()
        if (devices.isEmpty()) return null
        if (serverName.isNullOrBlank()) return devices.first()
        return devices.firstOrNull { it.name == serverName }
    }

    private fun deviceNamesDesc(devices: List<ShellDeviceConfig>): String =
        devices.joinToString(", ") { "\"${it.name}\"" }

    fun getTools(): List<Tool> = listOf(
        listShellsTool(),
        executeShellCommandTool(),
        fileReadTool(),
        fileWriteTool(),
        fileGlobTool(),
        fileGrepTool(),
    )

    // ── list_shells ─────────────────────────────────────────

    private fun listShellsTool() = Tool(
        name = "list_shells",
        description = "List configured terminal devices (Conch servers and SSH hosts) that the assistant can use for shell commands and file operations.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {},
                required = emptyList(),
            )
        },
        needsApproval = { false },
        execute = {
            val devices = currentDevices()
            // 只暴露非敏感元信息，避免把加密密钥序列化进模型上下文
            val body = buildJsonObject {
                put("type", "list_shells")
                put("servers", Json.parseToJsonElement(Json.encodeToString(devices.map {
                    buildJsonObject {
                        put("name", it.name)
                        put("type", it.type)
                        put("host", when (it.type) {
                            ShellDeviceConfig.TYPE_SSH -> "${it.sshUser}@${it.sshHost}:${it.sshPort}"
                            else -> it.serverUrl
                        })
                    }
                })))
            }
            listOf(UIMessagePart.Text(body.toString()))
        },
    )

    // ── execute_shell_command ───────────────────────────────

    private fun executeShellCommandTool() = Tool(
        name = "execute_shell_command",
        description = "Execute a shell command on a configured terminal device (Conch server or SSH host). " +
            "Use list_shells to see available devices.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("command", buildJsonObject {
                        put("type", "string")
                        put("description", "The shell command to execute.")
                    })
                    put("server", buildJsonObject {
                        put("type", "string")
                        put("description", "The device name. Optional if only one device is configured; use list_shells to see names.")
                    })
                    put("timeout_ms", buildJsonObject {
                        put("type", "integer")
                        put("description", "Timeout in milliseconds (optional, default 300000).")
                    })
                    put("workdir", buildJsonObject {
                        put("type", "string")
                        put("description", "Working directory (optional).")
                    })
                },
                required = listOf("command"),
            )
        },
        needsApproval = { true },
        execute = { args ->
            val obj = args.jsonObject
            val command = obj["command"]?.jsonPrimitive?.contentOrNull ?: return@Tool listOf(
                UIMessagePart.Text("{\"type\":\"execute_shell_command\",\"error\":\"missing_command\",\"message\":\"command is required\"}")
            )
            val serverName = obj["server"]?.jsonPrimitive?.contentOrNull
            val timeoutMs = obj["timeout_ms"]?.jsonPrimitive?.content?.toIntOrNull() ?: 300_000
            val workdir = obj["workdir"]?.jsonPrimitive?.contentOrNull ?: ""
            val device = findDevice(serverName)
            if (device == null) {
                return@Tool listOf(
                    UIMessagePart.Text(
                        jsonError(
                            "execute_shell_command",
                            if (serverName.isNullOrBlank()) "No terminal devices configured. Add one in Settings > Device Management first."
                            else "Device \"$serverName\" not found. Use list_shells to see available devices.",
                            command = command,
                        )
                    )
                )
            }
            val result = withContext(Dispatchers.IO) {
                try {
                    when (device.type) {
                        ShellDeviceConfig.TYPE_SSH -> {
                            val client = SshClient(
                                host = device.sshHost,
                                port = device.sshPort,
                                user = device.sshUser,
                                password = device.sshPassword,
                                pinnedHostKey = device.sshHostKey,
                                // TOFU：无 pin 时首次连接接受并捕获主机密钥（与 Agora 行为一致）
                                allowUnknownHostKey = true,
                            )
                            try {
                                val r = client.executeCommand(command, workdir, timeoutMs)
                                buildJsonObject {
                                    put("type", "execute_shell_command")
                                    put("server", device.name)
                                    put("command", command)
                                    put("exit_code", r.exitCode)
                                    put("output", (r.stdout + r.stderr).trimEnd())
                                }.toString()
                            } finally {
                                client.close()
                            }
                        }
                        else -> {
                            val client = ShellClient(
                                device.serverUrl.trimEnd('/'),
                                device.apiKey,
                                device.conchPublicKey,
                            )
                            if (!client.fetchPublicKey() && device.apiKey.isNotBlank()) {
                                jsonError(
                                    "execute_shell_command",
                                    client.lastError ?: "Conch public-key exchange failed",
                                    server = device.name,
                                    command = command,
                                )
                            } else {
                                val prepared = client.prepareRequest(command, timeoutMs, workdir)
                                val resp = runCatching {
                                    client.executeCommandSync(prepared)
                                }.getOrElse { e ->
                                    return@getOrElse jsonError(
                                        "execute_shell_command",
                                        describeConchRequestFailure(prepared.serverUrl, "/execute request", e as? Exception ?: Exception(e)),
                                        server = device.name,
                                        command = command,
                                    )
                                }
                                resp
                            }
                        }
                    }
                } catch (e: Exception) {
                    jsonError("execute_shell_command", e.message ?: "Unknown error", server = device.name, command = command)
                }
            }
            listOf(UIMessagePart.Text(result))
        },
    )

    // ── file_read ───────────────────────────────────────────

    private fun fileReadTool() = Tool(
        name = "file_read",
        description = "Read a file from a configured terminal device (Conch server or SSH host).",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Absolute path to the file.")
                    })
                    put("server", buildJsonObject {
                        put("type", "string")
                        put("description", "The device name. Optional if only one device is configured.")
                    })
                    put("offset", buildJsonObject {
                        put("type", "integer")
                        put("description", "Byte offset (optional).")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Max bytes to read (optional, default 1MB).")
                    })
                },
                required = listOf("path"),
            )
        },
        needsApproval = { false },
        execute = { args ->
            val obj = args.jsonObject
            val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@Tool listOf(
                UIMessagePart.Text("{\"type\":\"file_read\",\"error\":\"missing_path\",\"message\":\"path is required\"}")
            )
            val serverName = obj["server"]?.jsonPrimitive?.contentOrNull
            val offset = obj["offset"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val limit = obj["limit"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val device = findDevice(serverName)
            if (device == null) {
                return@Tool listOf(
                    UIMessagePart.Text(
                        jsonError(
                            "file_read",
                            if (serverName.isNullOrBlank()) "No terminal devices configured." else "Device \"$serverName\" not found.",
                        )
                    )
                )
            }
            val result = withContext(Dispatchers.IO) {
                try {
                    when (device.type) {
                        ShellDeviceConfig.TYPE_SSH -> {
                            val client = SshClient(
                                host = device.sshHost,
                                port = device.sshPort,
                                user = device.sshUser,
                                password = device.sshPassword,
                                pinnedHostKey = device.sshHostKey,
                                // TOFU：无 pin 时首次连接接受并捕获主机密钥（与 Agora 行为一致）
                                allowUnknownHostKey = true,
                            )
                            try {
                                val content = client.fileRead(path, offset, limit)
                                buildJsonObject {
                                    put("type", "file_read")
                                    put("server", device.name)
                                    put("path", path)
                                    put("content", content)
                                    put("lines", content.lines().size)
                                }.toString()
                            } finally {
                                client.close()
                            }
                        }
                        else -> {
                            val client = ShellClient(device.serverUrl.trimEnd('/'), device.apiKey, device.conchPublicKey)
                            val result = client.fileRead(path, offset, limit)
                            if (result.error != null) {
                                jsonError("file_read", result.error, server = device.name)
                            } else {
                                buildJsonObject {
                                    put("type", "file_read")
                                    put("server", device.name)
                                    put("path", path)
                                    put("content", result.content)
                                    put("lines", result.lines)
                                }.toString()
                            }
                        }
                    }
                } catch (e: Exception) {
                    jsonError("file_read", e.message ?: "Unknown error", server = device.name)
                }
            }
            listOf(UIMessagePart.Text(result))
        },
    )

    // ── file_write ──────────────────────────────────────────

    private fun fileWriteTool() = Tool(
        name = "file_write",
        description = "Write content to a file on a configured terminal device (Conch server or SSH host).",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Absolute path to the file.")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "Content to write.")
                    })
                    put("server", buildJsonObject {
                        put("type", "string")
                        put("description", "The device name. Optional if only one device is configured.")
                    })
                },
                required = listOf("path", "content"),
            )
        },
        needsApproval = { true },
        execute = { args ->
            val obj = args.jsonObject
            val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@Tool listOf(
                UIMessagePart.Text("{\"type\":\"file_write\",\"error\":\"missing_path\",\"message\":\"path is required\"}")
            )
            val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: ""
            val serverName = obj["server"]?.jsonPrimitive?.contentOrNull
            val device = findDevice(serverName)
            if (device == null) {
                return@Tool listOf(
                    UIMessagePart.Text(
                        jsonError(
                            "file_write",
                            if (serverName.isNullOrBlank()) "No terminal devices configured." else "Device \"$serverName\" not found.",
                        )
                    )
                )
            }
            val result = withContext(Dispatchers.IO) {
                try {
                    when (device.type) {
                        ShellDeviceConfig.TYPE_SSH -> {
                            val client = SshClient(
                                host = device.sshHost,
                                port = device.sshPort,
                                user = device.sshUser,
                                password = device.sshPassword,
                                pinnedHostKey = device.sshHostKey,
                                // TOFU：无 pin 时首次连接接受并捕获主机密钥（与 Agora 行为一致）
                                allowUnknownHostKey = true,
                            )
                            try {
                                client.fileWrite(path, content)
                                    ?.let { jsonError("file_write", it, server = device.name) }
                                    ?: buildJsonObject {
                                        put("type", "file_write")
                                        put("server", device.name)
                                        put("path", path)
                                        put("status", "ok")
                                    }.toString()
                            } finally {
                                client.close()
                            }
                        }
                        else -> {
                            val client = ShellClient(device.serverUrl.trimEnd('/'), device.apiKey, device.conchPublicKey)
                            client.fileWrite(path, content)
                                ?.let { jsonError("file_write", it, server = device.name) }
                                ?: buildJsonObject {
                                    put("type", "file_write")
                                    put("server", device.name)
                                    put("path", path)
                                    put("status", "ok")
                                }.toString()
                        }
                    }
                } catch (e: Exception) {
                    jsonError("file_write", e.message ?: "Unknown error", server = device.name)
                }
            }
            listOf(UIMessagePart.Text(result))
        },
    )

    // ── file_glob ───────────────────────────────────────────

    private fun fileGlobTool() = Tool(
        name = "file_glob",
        description = "List files on a configured terminal device matching a glob pattern.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("pattern", buildJsonObject {
                        put("type", "string")
                        put("description", "Glob pattern matched against file names (e.g. '*.go', '*.md').")
                    })
                    put("server", buildJsonObject {
                        put("type", "string")
                        put("description", "The device name. Optional if only one device is configured.")
                    })
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Base directory for the search (optional).")
                    })
                    put("depth", buildJsonObject {
                        put("type", "integer")
                        put("description", "Max directory levels to search below path (optional).")
                    })
                },
                required = listOf("pattern"),
            )
        },
        needsApproval = { false },
        execute = { args ->
            val obj = args.jsonObject
            val pattern = obj["pattern"]?.jsonPrimitive?.contentOrNull ?: return@Tool listOf(
                UIMessagePart.Text("{\"type\":\"file_glob\",\"error\":\"missing_pattern\",\"message\":\"pattern is required\"}")
            )
            val serverName = obj["server"]?.jsonPrimitive?.contentOrNull
            val basePath = obj["path"]?.jsonPrimitive?.contentOrNull ?: ""
            val depth = obj["depth"]?.jsonPrimitive?.content?.toIntOrNull()
            val device = findDevice(serverName)
            if (device == null) {
                return@Tool listOf(
                    UIMessagePart.Text(
                        jsonError(
                            "file_glob",
                            if (serverName.isNullOrBlank()) "No terminal devices configured." else "Device \"$serverName\" not found.",
                        )
                    )
                )
            }
            val result = withContext(Dispatchers.IO) {
                try {
                    when (device.type) {
                        ShellDeviceConfig.TYPE_SSH -> {
                            val client = SshClient(
                                host = device.sshHost,
                                port = device.sshPort,
                                user = device.sshUser,
                                password = device.sshPassword,
                                pinnedHostKey = device.sshHostKey,
                                // TOFU：无 pin 时首次连接接受并捕获主机密钥（与 Agora 行为一致）
                                allowUnknownHostKey = true,
                            )
                            try {
                                val files = client.fileGlob(pattern, basePath, depth)
                                buildJsonObject {
                                    put("type", "file_glob")
                                    put("server", device.name)
                                    put("pattern", pattern)
                                    put("files", Json.parseToJsonElement(Json.encodeToString(files)))
                                }.toString()
                            } finally {
                                client.close()
                            }
                        }
                        else -> {
                            val client = ShellClient(device.serverUrl.trimEnd('/'), device.apiKey, device.conchPublicKey)
                            val r = client.fileGlob(pattern, basePath, depth)
                            r.fold(
                                onSuccess = { files ->
                                    buildJsonObject {
                                        put("type", "file_glob")
                                        put("server", device.name)
                                        put("pattern", pattern)
                                        put("files", Json.parseToJsonElement(Json.encodeToString(files)))
                                    }.toString()
                                },
                                onFailure = { e ->
                                    jsonError("file_glob", e.message ?: "Glob failed", server = device.name)
                                },
                            )
                        }
                    }
                } catch (e: Exception) {
                    jsonError("file_glob", e.message ?: "Unknown error", server = device.name)
                }
            }
            listOf(UIMessagePart.Text(result))
        },
    )

    // ── file_grep ───────────────────────────────────────────

    private fun fileGrepTool() = Tool(
        name = "file_grep",
        description = "Search for a regex pattern in files on a configured terminal device.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("pattern", buildJsonObject {
                        put("type", "string")
                        put("description", "Regular expression pattern to search for.")
                    })
                    put("server", buildJsonObject {
                        put("type", "string")
                        put("description", "The device name. Optional if only one device is configured.")
                    })
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "File or directory to search in (optional).")
                    })
                    put("glob", buildJsonObject {
                        put("type", "string")
                        put("description", "Filter files by glob pattern (optional).")
                    })
                },
                required = listOf("pattern"),
            )
        },
        needsApproval = { false },
        execute = { args ->
            val obj = args.jsonObject
            val pattern = obj["pattern"]?.jsonPrimitive?.contentOrNull ?: return@Tool listOf(
                UIMessagePart.Text("{\"type\":\"file_grep\",\"error\":\"missing_pattern\",\"message\":\"pattern is required\"}")
            )
            val serverName = obj["server"]?.jsonPrimitive?.contentOrNull
            val basePath = obj["path"]?.jsonPrimitive?.contentOrNull ?: ""
            val fileGlob = obj["glob"]?.jsonPrimitive?.contentOrNull ?: ""
            val device = findDevice(serverName)
            if (device == null) {
                return@Tool listOf(
                    UIMessagePart.Text(
                        jsonError(
                            "file_grep",
                            if (serverName.isNullOrBlank()) "No terminal devices configured." else "Device \"$serverName\" not found.",
                        )
                    )
                )
            }
            val result = withContext(Dispatchers.IO) {
                try {
                    when (device.type) {
                        ShellDeviceConfig.TYPE_SSH -> {
                            val client = SshClient(
                                host = device.sshHost,
                                port = device.sshPort,
                                user = device.sshUser,
                                password = device.sshPassword,
                                pinnedHostKey = device.sshHostKey,
                                // TOFU：无 pin 时首次连接接受并捕获主机密钥（与 Agora 行为一致）
                                allowUnknownHostKey = true,
                            )
                            try {
                                val r = client.fileGrep(pattern, basePath, fileGlob)
                                r.fold(
                                    onSuccess = { matches ->
                                        buildJsonObject {
                                            put("type", "file_grep")
                                            put("server", device.name)
                                            put("pattern", pattern)
                                            put("matches", Json.parseToJsonElement(Json.encodeToString(matches)))
                                        }.toString()
                                    },
                                    onFailure = { e ->
                                        jsonError("file_grep", e.message ?: "Grep failed", server = device.name)
                                    },
                                )
                            } finally {
                                client.close()
                            }
                        }
                        else -> {
                            val client = ShellClient(device.serverUrl.trimEnd('/'), device.apiKey, device.conchPublicKey)
                            val r = client.fileGrep(pattern, basePath, fileGlob)
                            r.fold(
                                onSuccess = { matches ->
                                    buildJsonObject {
                                        put("type", "file_grep")
                                        put("server", device.name)
                                        put("pattern", pattern)
                                        put("matches", Json.parseToJsonElement(Json.encodeToString(matches)))
                                    }.toString()
                                },
                                onFailure = { e ->
                                    jsonError("file_grep", e.message ?: "Grep failed", server = device.name)
                                },
                            )
                        }
                    }
                } catch (e: Exception) {
                    jsonError("file_grep", e.message ?: "Unknown error", server = device.name)
                }
            }
            listOf(UIMessagePart.Text(result))
        },
    )

    private fun jsonError(type: String, message: String, server: String? = null, command: String? = null): String =
        buildJsonObject {
            put("type", type)
            put("error", "tool_error")
            put("message", message)
            server?.let { put("server", it) }
            command?.let { put("command", it) }
        }.toString()
}
