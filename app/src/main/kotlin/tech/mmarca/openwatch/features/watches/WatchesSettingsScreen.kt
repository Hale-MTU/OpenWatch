package tech.mmarca.openwatch.features.watches

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.ui.components.OpenWatchButton
import tech.mmarca.openwatch.ui.components.DeviceAvatar
import tech.mmarca.openwatch.ui.components.OpenWatchCard
import tech.mmarca.openwatch.ui.components.OsPermissionsDialog
import tech.mmarca.openwatch.ui.components.formatDeviceTime
import tech.mmarca.openwatch.ui.theme.Spacing
import tech.mmarca.openwatch.ui.components.OpenWatchOutlinedButton

/** The section on its own screen: the home screen embeds [WatchesSection] in its own scroll column. */
@Composable
fun WatchesSettingsScreen(
    viewModel: WatchesViewModel,
    onOpenWatch: (String) -> Unit,
) {
    WatchesSection(
        viewModel = viewModel,
        onOpenWatch = onOpenWatch,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = Spacing.md),
    )
}

/**
 * The Watches settings screen: the paired list plus the add flow. A watch
 * row only identifies and opens; every action lives in the device view.
 */
@Composable
fun WatchesSection(
    viewModel: WatchesViewModel,
    onOpenWatch: (String) -> Unit,
    modifier: Modifier = Modifier,
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
            // Told once, after the sheet closes. Inline: this scaffold has no snackbar host.
            OpenWatchCard(modifier = Modifier.padding(horizontal = Spacing.lg)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.dismissNotice() }
                        .padding(Spacing.lg),
                ) {
                    Text(
                        text = when (notice) {
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
                        onClick = viewModel::startAdd,
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
                onClick = viewModel::startAdd,
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
            onGrantAll = {
                // Queue the settings walks before the dialog: its callback drains them.
                viewModel.queueAllSpecialPermissions()
                requestOsPermissions(state.osPermissions.requestablePermissions)
            },
            onGrantRow = { row ->
                if (row.isSpecial) {
                    viewModel.queueSpecialPermission(row.id)
                    viewModel.openNextSpecialPermission()
                } else {
                    requestOsPermissions(row.permissions)
                }
            },
            onContinue = viewModel::openAddFlow,
            onDismiss = viewModel::dismissPermissionsGate,
        )
    }

    if (state.showAddFlow) {
        AddWatchDialog(viewModel = viewModel, state = state)
    }
}

private val WatchPermissionIconSize = 18.dp

@Composable
private fun WatchRow(
    device: BleSensorDevice,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OpenWatchCard(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(Spacing.lg),
        ) {
            DeviceAvatar()
            Spacer(modifier = Modifier.width(WatchRowIconGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.displayName,
                    style = MaterialTheme.typography.titleSmall,
                )
                // A watch is named after its Bluetooth name; its state says more.
                Text(
                    text = if (device.enabled) {
                        stringResource(R.string.settings_watch_connected)
                    } else {
                        stringResource(R.string.settings_watch_not_connected)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    // A watch reports its battery over GFDI, not the standard service.
                    text = device.lastSyncedAt?.let {
                        stringResource(
                            R.string.settings_watch_last_synced,
                            formatDeviceTime(it),
                        )
                    } ?: stringResource(R.string.settings_watch_never_synced),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun AddWatchDialog(
    viewModel: WatchesViewModel,
    state: WatchesUiState,
) {
    AlertDialog(
        // Bonding cannot be taken back halfway.
        onDismissRequest = { if (!state.isOnboarding) viewModel.closeAddFlow() },
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
                            onClick = { viewModel.selectDiscoveredDevice(device) },
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
                        onValueChange = viewModel::updateAddDisplayName,
                        label = { Text(stringResource(R.string.settings_watch_name_label)) },
                        singleLine = true,
                        enabled = !state.isOnboarding,
                    )
                    state.errorMessage?.let { message ->
                        Text(
                            text = message,
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
                        viewModel.onboardSelectedWatch()
                    },
                    enabled = !state.isOnboarding,
                ) {
                    Text(stringResource(R.string.settings_watch_pair_action))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = viewModel::closeAddFlow,
                enabled = !state.isOnboarding,
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** Gap between a row's leading icon and its text. */
private val WatchRowIconGap = 14.dp
