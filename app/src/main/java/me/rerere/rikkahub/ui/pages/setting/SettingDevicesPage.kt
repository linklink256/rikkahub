package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.device.SecretCrypto
import me.rerere.rikkahub.data.device.ShellDeviceConfig
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingDevicesPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val devices = settings.devices
    val scope = rememberCoroutineScope()
    val settingsStore = koinInject<SettingsStore>()

    var editingDevice by remember { mutableStateOf<ShellDeviceConfig?>(null) }
    var isNewDevice by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ShellDeviceConfig?>(null) }

    val openEditor: (ShellDeviceConfig?, Boolean) -> Unit = { device, isNew ->
        editingDevice = device
        isNewDevice = isNew
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_devices_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { openEditor(null, true) },
            ) {
                Icon(HugeIcons.Add01, null)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (devices.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.setting_devices_page_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                items(devices, key = { it.id }) { device ->
                    DeviceCard(
                        device = device,
                        onEdit = { openEditor(device, false) },
                        onDelete = { deleteTarget = device },
                    )
                }
            }
        }
    }

    // 删除确认
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.setting_devices_page_delete_title)) },
            text = { Text(stringResource(R.string.setting_devices_page_delete_message, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        settingsStore.deleteDevice(target.id)
                    }
                    deleteTarget = null
                }) {
                    Text(stringResource(R.string.setting_devices_page_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.setting_devices_page_cancel))
                }
            },
        )
    }

    // 编辑/新增弹窗
    if (editingDevice != null || isNewDevice) {
        DeviceEditorDialog(
            initial = editingDevice,
            isNew = isNewDevice,
            onDismiss = {
                editingDevice = null
                isNewDevice = false
            },
            onSave = { device ->
                scope.launch {
                    settingsStore.saveDevice(device)
                }
                editingDevice = null
                isNewDevice = false
            },
        )
    }
}

@Composable
private fun DeviceCard(
    device: ShellDeviceConfig,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = when (device.type) {
                        ShellDeviceConfig.TYPE_SSH ->
                            "SSH · ${device.sshUser}@${device.sshHost}:${device.sshPort}"
                        else ->
                            "Conch · ${device.serverUrl}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(HugeIcons.Edit02, null)
            }
            IconButton(onClick = onDelete) {
                Icon(HugeIcons.Delete01, null, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun DeviceEditorDialog(
    initial: ShellDeviceConfig?,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (ShellDeviceConfig) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: ShellDeviceConfig.TYPE_CONCH) }
    var serverUrl by remember { mutableStateOf(initial?.serverUrl ?: "") }
    var apiKey by remember { mutableStateOf(initial?.apiKey?.let { SecretCrypto.decrypt(it) } ?: "") }
    var conchPublicKey by remember { mutableStateOf(initial?.conchPublicKey ?: "") }
    var sshHost by remember { mutableStateOf(initial?.sshHost ?: "") }
    var sshPort by remember { mutableStateOf(initial?.sshPort?.toString() ?: "22") }
    var sshUser by remember { mutableStateOf(initial?.sshUser ?: "root") }
    var sshPassword by remember { mutableStateOf(initial?.sshPassword?.let { SecretCrypto.decrypt(it) } ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) stringResource(R.string.setting_devices_page_add) else stringResource(R.string.setting_devices_page_edit)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.setting_devices_page_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 类型选择：Conch / SSH
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TypeChip(
                        label = "Conch",
                        selected = type == ShellDeviceConfig.TYPE_CONCH,
                        onClick = { type = ShellDeviceConfig.TYPE_CONCH },
                    )
                    TypeChip(
                        label = "SSH",
                        selected = type == ShellDeviceConfig.TYPE_SSH,
                        onClick = { type = ShellDeviceConfig.TYPE_SSH },
                    )
                }

                if (type == ShellDeviceConfig.TYPE_CONCH) {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text(stringResource(R.string.setting_devices_page_server_url)) },
                        placeholder = { Text("https://host:port") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(stringResource(R.string.setting_devices_page_api_key)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = conchPublicKey,
                        onValueChange = { conchPublicKey = it },
                        label = { Text(stringResource(R.string.setting_devices_page_conch_public_key)) },
                        placeholder = { Text("(optional) base64url X25519 public key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = sshHost,
                        onValueChange = { sshHost = it },
                        label = { Text(stringResource(R.string.setting_devices_page_ssh_host)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = sshPort,
                        onValueChange = { sshPort = it },
                        label = { Text(stringResource(R.string.setting_devices_page_ssh_port)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = sshUser,
                        onValueChange = { sshUser = it },
                        label = { Text(stringResource(R.string.setting_devices_page_ssh_user)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = sshPassword,
                        onValueChange = { sshPassword = it },
                        label = { Text(stringResource(R.string.setting_devices_page_ssh_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val device = ShellDeviceConfig(
                    id = initial?.id ?: java.util.UUID.randomUUID().toString(),
                    name = name.trim().ifBlank { "Untitled" },
                    description = "",
                    type = type,
                    serverUrl = if (type == ShellDeviceConfig.TYPE_CONCH) serverUrl.trim() else "",
                    apiKey = if (type == ShellDeviceConfig.TYPE_CONCH && apiKey.isNotBlank()) SecretCrypto.encrypt(apiKey) else "",
                    conchPublicKey = if (type == ShellDeviceConfig.TYPE_CONCH) conchPublicKey.trim() else "",
                    sshHost = if (type == ShellDeviceConfig.TYPE_SSH) sshHost.trim() else "",
                    sshPort = sshPort.toIntOrNull() ?: 22,
                    sshUser = if (type == ShellDeviceConfig.TYPE_SSH) sshUser.trim().ifBlank { "root" } else "",
                    sshPassword = if (type == ShellDeviceConfig.TYPE_SSH && sshPassword.isNotBlank()) SecretCrypto.encrypt(sshPassword) else "",
                    sshHostKey = initial?.sshHostKey ?: "",
                )
                onSave(device)
            }) {
                Text(stringResource(R.string.setting_devices_page_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.setting_devices_page_cancel))
            }
        },
    )
}

@Composable
private fun TypeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}
