package me.rerere.rikkahub.ui.pages.extensions.remote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.device.ShellDeviceConfig
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun RemoteDevicePage(vm: RemoteDeviceVM = koinViewModel()) {
    val navController = LocalNavController.current
    val devices by vm.devices.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<ShellDeviceConfig?>(null) }
    var deleteTarget by remember { mutableStateOf<ShellDeviceConfig?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.remote_device_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(HugeIcons.Add01, contentDescription = null)
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (devices.isEmpty()) {
                item { EmptyRemoteDeviceState() }
            }
            items(devices, key = { it.id }) { device ->
                RemoteDeviceCard(
                    device = device,
                    onEdit = { editTarget = device },
                    onDelete = { deleteTarget = device },
                    onOpen = { navController.navigate(Screen.RemoteDeviceDetail(device.id)) },
                )
            }
        }
    }

    if (showAddDialog) {
        RemoteDeviceEditDialog(
            title = stringResource(R.string.remote_device_page_add),
            initial = null,
            onDismiss = { showAddDialog = false },
            onConfirm = { device ->
                vm.saveDevice(device)
                showAddDialog = false
            },
        )
    }

    editTarget?.let { device ->
        RemoteDeviceEditDialog(
            title = stringResource(R.string.remote_device_page_edit),
            initial = device,
            onDismiss = { editTarget = null },
            onConfirm = { updated ->
                vm.saveDevice(updated)
                editTarget = null
            },
        )
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.remote_device_page_delete),
        confirmText = stringResource(R.string.common_delete),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            deleteTarget?.let { vm.deleteDevice(it.id) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.remote_device_page_delete_confirm))
    }
}

@Composable
private fun EmptyRemoteDeviceState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = HugeIcons.ComputerTerminal01,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.remote_device_page_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.remote_device_page_empty_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RemoteDeviceCard(
    device: ShellDeviceConfig,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (device.type == ShellDeviceConfig.TYPE_SSH) HugeIcons.ComputerTerminal01 else HugeIcons.ServerStack01,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = device.name.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = device.remoteAddressLabel(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(HugeIcons.MoreVertical, contentDescription = null)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_rename)) },
                        leadingIcon = { Icon(HugeIcons.Edit01, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/** 设备地址标签：SSH → user@host:port，Conch → serverUrl */
fun ShellDeviceConfig.remoteAddressLabel(): String = when (type) {
    ShellDeviceConfig.TYPE_SSH -> "$sshUser@$sshHost:$sshPort"
    else -> serverUrl
}

@Composable
private fun RemoteDeviceEditDialog(
    title: String,
    initial: ShellDeviceConfig?,
    onDismiss: () -> Unit,
    onConfirm: (ShellDeviceConfig) -> Unit,
) {
    var name by rememberSaveable(initial?.name) { mutableStateOf(initial?.name ?: "") }
    var type by rememberSaveable(initial?.type) { mutableStateOf(initial?.type ?: ShellDeviceConfig.TYPE_CONCH) }
    var serverUrl by rememberSaveable(initial?.serverUrl) { mutableStateOf(initial?.serverUrl ?: "") }
    var apiKey by rememberSaveable(initial?.apiKey) { mutableStateOf(initial?.apiKey ?: "") }
    var conchPublicKey by rememberSaveable(initial?.conchPublicKey) { mutableStateOf(initial?.conchPublicKey ?: "") }
    var sshHost by rememberSaveable(initial?.sshHost) { mutableStateOf(initial?.sshHost ?: "") }
    var sshPort by rememberSaveable(initial?.sshPort?.toString()) { mutableStateOf(initial?.sshPort?.toString() ?: "22") }
    var sshUser by rememberSaveable(initial?.sshUser) { mutableStateOf(initial?.sshUser ?: "root") }
    var sshPassword by rememberSaveable(initial?.sshPassword) { mutableStateOf(initial?.sshPassword ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
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
                    label = { Text(stringResource(R.string.remote_device_page_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DeviceTypeChip(
                        label = "SSH",
                        selected = type == ShellDeviceConfig.TYPE_SSH,
                        onClick = { type = ShellDeviceConfig.TYPE_SSH },
                    )
                    DeviceTypeChip(
                        label = "Conch",
                        selected = type == ShellDeviceConfig.TYPE_CONCH,
                        onClick = { type = ShellDeviceConfig.TYPE_CONCH },
                    )
                }
                if (type == ShellDeviceConfig.TYPE_CONCH) {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text(stringResource(R.string.remote_device_page_server_url)) },
                        placeholder = { Text("https://host:port") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(stringResource(R.string.remote_device_page_api_key)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = conchPublicKey,
                        onValueChange = { conchPublicKey = it },
                        label = { Text(stringResource(R.string.remote_device_page_conch_public_key)) },
                        placeholder = { Text("(optional) base64url X25519 public key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = sshHost,
                        onValueChange = { sshHost = it },
                        label = { Text(stringResource(R.string.remote_device_page_ssh_host)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = sshPort,
                        onValueChange = { sshPort = it },
                        label = { Text(stringResource(R.string.remote_device_page_ssh_port)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = sshUser,
                        onValueChange = { sshUser = it },
                        label = { Text(stringResource(R.string.remote_device_page_ssh_user)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = sshPassword,
                        onValueChange = { sshPassword = it },
                        label = { Text(stringResource(R.string.remote_device_page_ssh_password)) },
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
                    apiKey = if (type == ShellDeviceConfig.TYPE_CONCH && apiKey.isNotBlank()) apiKey.trim() else "",
                    conchPublicKey = if (type == ShellDeviceConfig.TYPE_CONCH) conchPublicKey.trim() else "",
                    sshHost = if (type == ShellDeviceConfig.TYPE_SSH) sshHost.trim() else "",
                    sshPort = sshPort.toIntOrNull() ?: 22,
                    sshUser = if (type == ShellDeviceConfig.TYPE_SSH) sshUser.trim().ifBlank { "root" } else "",
                    sshPassword = if (type == ShellDeviceConfig.TYPE_SSH && sshPassword.isNotBlank()) sshPassword.trim() else "",
                    sshHostKey = initial?.sshHostKey ?: "",
                )
                onConfirm(device)
            }) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun DeviceTypeChip(
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
