package me.rerere.rikkahub.ui.pages.extensions.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.device.ShellClient
import me.rerere.rikkahub.data.device.ShellDeviceConfig
import me.rerere.rikkahub.data.device.SshClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RemoteFileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
)

data class RemoteDeviceDetailState(
    val device: ShellDeviceConfig? = null,
    val path: String = "",
    val entries: List<RemoteFileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val connectionTest: String? = null,
    val testing: Boolean = false,
)

class RemoteDeviceDetailVM(
    private val deviceId: String,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val _state = MutableStateFlow(RemoteDeviceDetailState())
    val state = _state.asStateFlow()

    val device: StateFlow<ShellDeviceConfig?> = settingsStore.settingsFlow
        .map { it.devices.firstOrNull { d -> d.id == deviceId } }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    init {
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                val dev = settings.devices.firstOrNull { it.id == deviceId }
                _state.value = _state.value.copy(device = dev)
            }
        }
        refresh()
    }

    fun refresh() {
        val dev = _state.value.device ?: return
        val currentPath = _state.value.path
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val entries = withContext(Dispatchers.IO) {
                    listRemoteDirectory(dev, currentPath)
                }
                _state.value = _state.value.copy(entries = entries, loading = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "Failed to list directory",
                )
            }
        }
    }

    fun open(entry: RemoteFileEntry) {
        if (!entry.isDirectory) return
        _state.value = _state.value.copy(path = entry.path, entries = emptyList())
        refresh()
    }

    fun goUp() {
        val path = _state.value.path
        if (path.isBlank()) return
        val parent = path.trimEnd('/').substringBeforeLast('/', "").let {
            if (it == "") "" else it
        }
        _state.value = _state.value.copy(path = parent, entries = emptyList())
        refresh()
    }

    fun testConnection() {
        val dev = _state.value.device ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(testing = true, connectionTest = null)
            val result = withContext(Dispatchers.IO) {
                try {
                    when (dev.type) {
                        ShellDeviceConfig.TYPE_SSH -> {
                            val client = SshClient(
                                host = dev.sshHost,
                                port = dev.sshPort,
                                user = dev.sshUser,
                                password = dev.sshPassword,
                                pinnedHostKey = dev.sshHostKey,
                                allowUnknownHostKey = true,
                            )
                            try {
                                val r = client.executeCommand("echo ok", execTimeoutMs = 15_000)
                                if (r.exitCode == 0) "Connected: ${r.stdout.trim()}" else "SSH failed: ${r.stderr.trim()}"
                            } finally {
                                client.close()
                            }
                        }
                        else -> {
                            val client = ShellClient(dev.serverUrl.trimEnd('/'), dev.apiKey, dev.conchPublicKey)
                            if (client.fetchPublicKey()) "Conch connected (public key verified)" else "Conch failed: ${client.lastError}"
                        }
                    }
                } catch (e: Exception) {
                    "Connection failed: ${e.message}"
                }
            }
            _state.value = _state.value.copy(testing = false, connectionTest = result)
        }
    }

    private suspend fun listRemoteDirectory(
        device: ShellDeviceConfig,
        path: String,
    ): List<RemoteFileEntry> = when (device.type) {
        ShellDeviceConfig.TYPE_SSH -> {
            val client = SshClient(
                host = device.sshHost,
                port = device.sshPort,
                user = device.sshUser,
                password = device.sshPassword,
                pinnedHostKey = device.sshHostKey,
                allowUnknownHostKey = true,
            )
            try {
                client.listDirectory(path).map {
                    RemoteFileEntry(
                        name = it.name,
                        path = it.path,
                        isDirectory = it.isDirectory,
                        sizeBytes = it.sizeBytes,
                    )
                }
            } finally {
                client.close()
            }
        }
        else -> {
            val client = ShellClient(device.serverUrl.trimEnd('/'), device.apiKey, device.conchPublicKey)
            client.listDirectory(path).map {
                RemoteFileEntry(
                    name = it.name,
                    path = it.path,
                    isDirectory = it.isDirectory,
                    sizeBytes = it.sizeBytes,
                )
            }
        }
    }
}
