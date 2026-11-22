package tech.mmarca.openwatch.features.watches

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
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
import tech.mmarca.openwatch.core.presentation.resolve
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.devices.garmin.GarminOnboardStep
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleDiscoveredDevice
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration
import tech.mmarca.openwatch.domain.model.OsPermissionCatalog
import tech.mmarca.openwatch.domain.model.OsPermissionId
import tech.mmarca.openwatch.domain.model.OsPermissionRow
import tech.mmarca.openwatch.ui.components.DeviceListRow
import tech.mmarca.openwatch.ui.components.OpenWatchButton
import tech.mmarca.openwatch.ui.components.OpenWatchCard
import tech.mmarca.openwatch.ui.components.OsPermissionsDialog
import tech.mmarca.openwatch.ui.components.formatDeviceTime
import tech.mmarca.openwatch.ui.theme.OpenWatchTheme
import tech.mmarca.openwatch.ui.theme.Spacing
import tech.mmarca.openwatch.ui.components.OpenWatchOutlinedButton

/**
 * The paired watches' section: the only place that knows the ViewModel. It refreshes the
 * grants on the way in and on every resume, stops the scan on the way out, and owns the
 * permission request and the settings walk; [WatchesSection] draws.
 */
@Composable
fun WatchesSectionRoute(
    onOpenWatch: (String) -> Unit,
    viewModel: WatchesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // One batched dialog for the runtime permissions; settings-screen ones follow through the queue.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.refreshOsPermissions()
        viewModel.openNextSpecialPermission()
    }

    fun requestOsPermissions(permissions: List<String>) {
        if (permissions.isEmpty()) {
            viewModel.openNextSpecialPermission()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    DisposableEffect(Unit) {
        viewModel.refresh()
        onDispose { viewModel.stopScan() }
    }

    // Returning from a settings screen is the cue to hand over the next one.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshOsPermissions()
        viewModel.openNextSpecialPermission()
    }

    WatchesSection(
        state = state,
        onOpenWatch = onOpenWatch,
        onAdd = viewModel::startAdd,
        onDismissNotice = viewModel::dismissNotice,
        onGrantAllPermissions = {
            // Queue the settings walks before the dialog: its callback drains them.
            viewModel.queueAllSpecialPermissions()
            requestOsPermissions(state.osPermissions.requestablePermissions)
        },
        onGrantPermissionRow = { row ->
            if (row.isSpecial) {
                viewModel.queueSpecialPermission(row.id)
                viewModel.openNextSpecialPermission()
            } else {
                requestOsPermissions(row.permissions)
            }
        },
        onContinueToAdd = viewModel::openAddFlow,
        onDismissPermissionsGate = viewModel::dismissPermissionsGate,
        onSelectDevice = viewModel::selectDiscoveredDevice,
        onAddNameChange = viewModel::updateAddDisplayName,
        onPair = viewModel::onboardSelectedWatch,
        onCloseAddFlow = viewModel::closeAddFlow,
    )
}

/**
 * The paired list plus the add flow, as drawn from [state]. A watch
 * row only identifies and opens; every action lives in the device view.
 */
@Composable
fun WatchesSection(
    state: WatchesUiState,
    onOpenWatch: (String) -> Unit,
    onAdd: () -> Unit,
    onDismissNotice: () -> Unit,
    onGrantAllPermissions: () -> Unit,
    onGrantPermissionRow: (OsPermissionRow) -> Unit,
    onContinueToAdd: () -> Unit,
    onDismissPermissionsGate: () -> Unit,
    onSelectDevice: (BleDiscoveredDevice) -> Unit,
    onAddNameChange: (String) -> Unit,
    onPair: () -> Unit,
    onCloseAddFlow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.settings_watches_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.lg),
        )

        state.onboardNotice?.let { notice ->
            // Told once, after the sheet closes, where the watch now shows.
            OpenWatchCard(modifier = Modifier.padding(horizontal = Spacing.lg)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDismissNotice() }
                        .padding(Spacing.lg),
                ) {
                    Text(
                        text = when (notice) {
                            WatchOnboardNotice.UNSUPPORTED_TRANSPORT ->
                                stringResource(R.string.settings_watch_unsupported_notice)

                            WatchOnboardNotice.NO_COMPANION ->
                                stringResource(R.string.settings_watch_no_companion_notice)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.watches.isEmpty()) {
            OpenWatchCard(modifier = Modifier.padding(horizontal = Spacing.lg)) {
                Column(
                    modifier = Modifier.padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Text(
                        text = stringResource(R.string.settings_watches_empty_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.settings_watches_empty_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OpenWatchButton(
                        onClick = onAdd,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = null,
                            modifier = Modifier.size(WatchPermissionIconSize),
                        )
                        Text(
                            text = stringResource(R.string.settings_watches_add),
                            modifier = Modifier.padding(start = Spacing.sm),
                        )
                    }
                }
            }
        } else {
            state.watches.forEach { watch ->
                WatchRow(
                    device = watch,
                    onOpen = { onOpenWatch(watch.id) },
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
            OpenWatchOutlinedButton(
                onClick = onAdd,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    modifier = Modifier.size(WatchPermissionIconSize),
                )
                Text(
                    text = stringResource(R.string.settings_watches_add),
                    modifier = Modifier.padding(start = Spacing.sm),
                )
            }
        }
    }

    if (state.showPermissionsGate) {
        OsPermissionsDialog(
            catalog = state.osPermissions,
            title = stringResource(R.string.watch_permissions_title),
            body = stringResource(R.string.watch_permissions_body),
            continueLabel = stringResource(R.string.settings_watches_add),
            onGrantAll = onGrantAllPermissions,
            onGrantRow = onGrantPermissionRow,
            onContinue = onContinueToAdd,
            onDismiss = onDismissPermissionsGate,
        )
    }

    if (state.showAddFlow) {
        AddWatchDialog(
            state = state,
            onSelectDevice = onSelectDevice,
            onNameChange = onAddNameChange,
            onPair = onPair,
            onClose = onCloseAddFlow,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun WatchesSectionPreview() {
    OpenWatchTheme {
        PreviewWatchesSection(state = WatchesUiState(watches = previewWatches()))
    }
}

@Preview(showBackground = true)
@Composable
private fun WatchesSectionPermissionsGatePreview() {
    OpenWatchTheme {
        PreviewWatchesSection(
            state = WatchesUiState(
                osPermissions = OsPermissionCatalog(
                    rows = listOf(
                        OsPermissionRow(
                            id = OsPermissionId.BLUETOOTH,
                            permissions = listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"),
                            granted = true,
                        ),
                        OsPermissionRow(
                            id = OsPermissionId.NOTIFICATIONS,
                            permissions = listOf("android.permission.POST_NOTIFICATIONS"),
                            granted = false,
                        ),
                        OsPermissionRow(id = OsPermissionId.BATTERY_OPTIMIZATION, permissions = emptyList(), granted = false),
                        OsPermissionRow(id = OsPermissionId.NOTIFICATION_FORWARDING, permissions = emptyList(), granted = false),
                    ),
                ),
                showPermissionsGate = true,
            ),
        )
    }
}

@Composable
private fun PreviewWatchesSection(state: WatchesUiState) {
    WatchesSection(
        state = state,
        onOpenWatch = {},
        onAdd = {},
        onDismissNotice = {},
        onGrantAllPermissions = {},
        onGrantPermissionRow = {},
        onContinueToAdd = {},
        onDismissPermissionsGate = {},
        onSelectDevice = {},
        onAddNameChange = {},
        onPair = {},
        onCloseAddFlow = {},
    )
}

/** Two paired watches: one connected and synced a while ago, one added and never synced. */
private fun previewWatches(): List<BleSensorDevice> = listOf(
    BleSensorDevice(
        id = "forerunner-265",
        displayName = "Forerunner 265",
        address = "D4:3A:2C:7E:51:09",
        bluetoothName = "Forerunner 265",
        capabilities = emptySet(),
        enabled = true,
        wheelCircumferenceMm = null,
        addedAt = Instant.now().minus(Duration.ofDays(40)),
        kind = BleDeviceKind.WATCH,
        lastSyncedAt = Instant.now().minus(Duration.ofMinutes(25)),
    ),
    BleSensorDevice(
        id = "venu-3",
        displayName = "Venu 3",
        address = "F0:8B:16:C2:9D:44",
        bluetoothName = "Venu 3",
        capabilities = emptySet(),
        enabled = false,
        wheelCircumferenceMm = null,
        addedAt = Instant.now().minus(Duration.ofMinutes(5)),
        kind = BleDeviceKind.WATCH,
    ),
)

private val WatchPermissionIconSize = 18.dp

@Composable
private fun WatchRow(
    device: BleSensorDevice,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A watch is named after its Bluetooth name; its state says more. It reports its battery
    // over GFDI, not the standard service, so the row shows the last sync instead.
    DeviceListRow(
        name = device.displayName,
        status = stringResource(if (device.enabled) R.string.settings_watch_connected else R.string.settings_watch_not_connected),
        detail = device.lastSyncedAt?.let { stringResource(R.string.settings_watch_last_synced, formatDeviceTime(it)) }
            ?: stringResource(R.string.settings_watch_never_synced),
        onOpen = onOpen,
        modifier = modifier,
    )
}

@Composable
private fun AddWatchDialog(
    state: WatchesUiState,
    onSelectDevice: (BleDiscoveredDevice) -> Unit,
    onNameChange: (String) -> Unit,
    onPair: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        // Bonding cannot be taken back halfway.
        onDismissRequest = { if (!state.isOnboarding) onClose() },
        title = { Text(stringResource(R.string.settings_watches_add)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = if (state.isScanning) {
                        stringResource(R.string.settings_sensors_scanning)
                    } else {
                        stringResource(R.string.settings_sensors_scan_stopped)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.discoveredWatches.isEmpty() && state.selectedDevice == null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.BluetoothSearching,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.settings_watches_scan_empty),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (state.selectedDevice == null) {
                    state.discoveredWatches.forEach { device ->
                        OpenWatchOutlinedButton(
                            onClick = { onSelectDevice(device) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(device.name ?: device.address)
                                Text(
                                    text = device.address,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
                state.selectedDevice?.let {
                    OutlinedTextField(
                        value = state.addDisplayName,
                        onValueChange = onNameChange,
                        label = { Text(stringResource(R.string.settings_watch_name_label)) },
                        singleLine = true,
                        enabled = !state.isOnboarding,
                    )
                    // A GFDI device picks no capabilities. WearOS has no bond or probe steps.
                    if (state.addingIntegration != DeviceIntegration.WEAROS) {
                        WatchPairSteps(step = state.onboardStep)
                    }
                    state.error.resolve()?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (state.companionRequired) {
                        Text(
                            text = stringResource(R.string.settings_watch_wearos_companion_required),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (state.selectedDevice != null) {
                TextButton(
                    onClick = {
                        // The dialog owns the close: a refused pairing leaves the sheet open.
                        onPair()
                    },
                    enabled = !state.isOnboarding,
                ) {
                    Text(stringResource(R.string.settings_watch_pair_action))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onClose,
                enabled = !state.isOnboarding,
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** The platform steps of onboarding, as a checklist. [step] is null outside pairing. */
@Composable
private fun WatchPairSteps(step: GarminOnboardStep?) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = stringResource(R.string.settings_watch_pair_title),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = stringResource(R.string.settings_watch_pair_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.padding(top = Spacing.xs))
        WatchPairStepRow(
            label = stringResource(R.string.settings_watch_step_bonding),
            active = step == GarminOnboardStep.BONDING,
            done = step == GarminOnboardStep.ASSOCIATING || step == GarminOnboardStep.PROBING,
        )
        WatchPairStepRow(
            label = stringResource(R.string.settings_watch_step_associating),
            active = step == GarminOnboardStep.ASSOCIATING,
            done = step == GarminOnboardStep.PROBING,
        )
        Text(
            text = stringResource(R.string.settings_watch_step_associating_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.xxxl),
        )
        WatchPairStepRow(
            label = stringResource(R.string.settings_watch_step_probing),
            active = step == GarminOnboardStep.PROBING,
            done = false,
        )
    }
}

@Composable
private fun WatchPairStepRow(
    label: String,
    active: Boolean,
    done: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            active -> CircularProgressIndicator(
                modifier = Modifier.size(Spacing.lg),
                strokeWidth = WatchProgressStroke,
            )

            done -> Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(WatchPermissionIconSize),
                tint = MaterialTheme.colorScheme.primary,
            )

            else -> Icon(
                imageVector = Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                modifier = Modifier.size(WatchPermissionIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (active) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** The small in-row progress spinner's stroke. */
private val WatchProgressStroke = 2.dp
/** Gap between a row's leading icon and its text. */
