package me.rerere.rikkahub.data.device

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 终端设备配置（移植自 Agora 的 ShellDeviceConfig）
 *
 * - type = "conch"：Conch 协议远程 Shell（ECDH + AES-256-GCM 加密）
 * - type = "ssh"：SSH 设备
 *
 * 密码字段（apiKey / sshPassword）经 [SecretCrypto] 加密后落盘，不存明文。
 */
@Serializable
data class ShellDeviceConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val type: String = "conch", // "conch" | "ssh"
    // Conch fields (type=conch)
    val serverUrl: String = "",
    val apiKey: String = "",
    val conchPublicKey: String = "",
    // SSH fields (type=ssh)
    val sshHost: String = "",
    val sshPort: Int = 22,
    val sshUser: String = "root",
    val sshPassword: String = "",
    // Pinned SSH host key (base64 of the server public-key blob). Blank = not yet
    // pinned (trust-on-first-use); once set, connections must match or are rejected.
    val sshHostKey: String = "",
) {
    companion object {
        const val TYPE_CONCH = "conch"
        const val TYPE_SSH = "ssh"
    }
}
