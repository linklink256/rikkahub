package me.rerere.rikkahub.ui.pages.extensions.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.device.ShellDeviceConfig

class RemoteDeviceVM(
    private val settingsStore: SettingsStore,
) : ViewModel() {
    val devices: StateFlow<List<ShellDeviceConfig>> = settingsStore.settingsFlow
        .map { it.devices }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun saveDevice(device: ShellDeviceConfig) {
        viewModelScope.launch {
            settingsStore.saveDevice(device)
        }
    }

    fun deleteDevice(deviceId: String) {
        viewModelScope.launch {
            settingsStore.deleteDevice(deviceId)
        }
    }
}
