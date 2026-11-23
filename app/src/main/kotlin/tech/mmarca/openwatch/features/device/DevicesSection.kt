package tech.mmarca.openwatch.features.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Duration
import java.time.Instant
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.core.presentation.resolve
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.OsPermissionRow
import tech.mmarca.openwatch.ui.components.DeviceListRow
import tech.mmarca.openwatch.ui.components.OpenWatchOutlinedButton
import tech.mmarca.openwatch.ui.components.OsPermissionsDialog
import tech.mmarca.openwatch.ui.components.SectionHeader
import tech.mmarca.openwatch.ui.components.formatDeviceTime
import tech.mmarca.openwatch.ui.components.rememberRuntimePermissionRequester
import tech.mmarca.openwatch.ui.theme.OpenWatchTheme
import tech.mmarca.openwatch.ui.theme.Spacing

/**
 * The Home section of the devices a family pairs through the app's own flow: the only place
 * that knows its ViewModel. It owns the Bluetooth grant request and stops the scan on the way
 * out; [DevicesSection] draws. Nothing shows while no family pairs this way.
 */
@Composable
fun DevicesSectionRoute(
    onOpenDevice: (String) -> Unit,
    /** The device shown beside the list on a wide screen, marked in it. */
    selectedDeviceId: String? = null,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionRequester = rememberRuntimePermissionRequester { viewModel.refreshOsPermissions() }
    DisposableEffect(Unit) {
        onDispose { viewModel.stopScan() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshOsPermissions() }

    if (!state.available) return
    DevicesSection(
        state = state,
        onOpenDevice = onOpenDevice,
        selectedDeviceId = selectedDeviceId,
        actions = DevicesSectionActions(
            onAdd = viewModel::startAdd,
            onGrantPermissions = { permissionRequester.request(state.osPermissions.requestablePermissions) },
            onGrantPermissionRow = { row -> permissionRequester.request(row.permissions) },
            onContinueToAdd = viewModel::openAddFlow,
            onDismissPermissionsGate = viewModel::dismissPermissionsGate,
            onSelect = viewModel::select,
            onNameChange = viewModel::updateName,
            onPair = viewModel::pair,
            onRescan = viewModel::startScan,
            onClose = viewModel::closeAddFlow,
        ),
    )
}

/** What the section's controls do. Each does nothing by default, for a preview. */
class DevicesSectionActions(
    val onAdd: () -> Unit = {},
    val onGrantPermissions: () -> Unit = {},
    val onGrantPermissionRow: (OsPermissionRow) -> Unit = {},
    val onContinueToAdd: () -> Unit = {},
    val onDismissPermissionsGate: () -> Unit = {},
    val onSelect: (DeviceCandidate) -> Unit = {},
    val onNameChange: (String) -> Unit = {},
    val onPair: () -> Unit = {},
    val onRescan: () -> Unit = {},
    val onClose: () -> Unit = {},
)

/** The section's header, its paired devices, the add button and, while open, the gate or the add dialog. */
@Composable
fun DevicesSection(
    state: DevicesUiState,
    onOpenDevice: (String) -> Unit,
    selectedDeviceId: String?,
    actions: DevicesSectionActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader(stringResource(R.string.home_section_devices))
        Text(
            text = stringResource(R.string.devices_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.lg),
        )
        state.devices.forEach { device ->
            DeviceRow(
                device = device,
                selected = device.id == selectedDeviceId,
                onOpen = { onOpenDevice(device.id) },
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
        }
        OpenWatchOutlinedButton(
            onClick = actions.onAdd,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg),
        ) {
            Icon(imageVector = Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(DevicesIconSize))
            Text(text = stringResource(R.string.devices_add), modifier = Modifier.padding(start = Spacing.sm))
        }
    }

    if (state.showPermissionsGate) {
        OsPermissionsDialog(
            catalog = state.osPermissions,
            title = stringResource(R.string.devices_permissions_title),
            body = stringResource(R.string.devices_permissions_body),
            continueLabel = stringResource(R.string.devices_add),
            onGrantAll = actions.onGrantPermissions,
            onGrantRow = actions.onGrantPermissionRow,
            onContinue = actions.onContinueToAdd,
            onDismiss = actions.onDismissPermissionsGate,
        )
    }
    if (state.showAddFlow) AddDeviceDialog(state, actions)
}

/** A paired device: its name, whether it is enabled, and when its last reading came. */
@Composable
private fun DeviceRow(device: BleSensorDevice, selected: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    DeviceListRow(
        name = device.displayName,
        status = stringResource(if (device.enabled) R.string.settings_watch_connected else R.string.settings_watch_not_connected),
        detail = device.lastSyncedAt?.let { stringResource(R.string.devices_last_reading, formatDeviceTime(it)) }
            ?: stringResource(R.string.devices_no_reading),
        onOpen = onOpen,
        modifier = modifier,
        icon = Icons.Outlined.Sensors,
        selected = selected,
    )
}

/** Scan, choose, name, pair. Pairing cannot be taken back halfway, so the dialog holds while it runs. */
@Composable
private fun AddDeviceDialog(state: DevicesUiState, actions: DevicesSectionActions) {
    AlertDialog(
        onDismissRequest = { if (!state.isPairing) actions.onClose() },
        title = { Text(stringResource(R.string.devices_add)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                val selected = state.selected
                if (selected == null) {
                    CandidateList(state, actions)
                } else {
                    OutlinedTextField(
                        value = state.nameInput,
                        onValueChange = actions.onNameChange,
                        label = { Text(stringResource(R.string.settings_watch_name_label)) },
                        singleLine = true,
                        enabled = !state.isPairing,
                    )
                    if (state.isPairing) {
                        CircularProgressIndicator(modifier = Modifier.size(Spacing.xl))
                        Text(text = stringResource(R.string.devices_pairing), style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.error.resolve()?.let { message ->
                    Text(text = message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when {
                state.selected != null -> TextButton(onClick = actions.onPair, enabled = !state.isPairing) {
                    Text(stringResource(R.string.settings_watch_pair_action))
                }
                !state.isScanning -> TextButton(onClick = actions.onRescan) { Text(stringResource(R.string.devices_scan_again)) }
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onClose, enabled = !state.isPairing) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun CandidateList(state: DevicesUiState, actions: DevicesSectionActions) {
    Text(
        text = stringResource(if (state.isScanning) R.string.settings_sensors_scanning else R.string.settings_sensors_scan_stopped),
        style = MaterialTheme.typography.bodySmall,
    )
    if (state.candidates.isEmpty()) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.BluetoothSearching,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(text = stringResource(R.string.devices_scan_empty), style = MaterialTheme.typography.bodySmall)
        return
    }
    state.candidates.forEach { candidate ->
        OpenWatchOutlinedButton(onClick = { actions.onSelect(candidate) }, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(candidate.device.name ?: candidate.device.address)
                Text(text = candidate.device.address, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DevicesSectionPreview() {
    OpenWatchTheme {
        DevicesSection(
            state = DevicesUiState(
                available = true,
                devices = listOf(
                    BleSensorDevice(
                        id = "strap",
                        displayName = "Chest strap",
                        address = "C8:2E:47:10:5A:3B",
                        bluetoothName = "Chest strap",
                        capabilities = emptySet(),
                        enabled = true,
                        wheelCircumferenceMm = null,
                        addedAt = Instant.now().minus(Duration.ofDays(3)),
                        kind = BleDeviceKind.SENSOR,
                        lastSyncedAt = Instant.now().minus(Duration.ofMinutes(12)),
                    ),
                ),
            ),
            onOpenDevice = {},
            selectedDeviceId = null,
            actions = DevicesSectionActions(),
        )
    }
}

private val DevicesIconSize = 18.dp
