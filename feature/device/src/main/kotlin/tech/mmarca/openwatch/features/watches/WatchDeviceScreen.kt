package tech.mmarca.openwatch.features.watches

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.mmarca.openwatch.features.device.R
import tech.mmarca.openwatch.core.presentation.formatPercent
import tech.mmarca.openwatch.devices.core.DeviceFeature
import tech.mmarca.openwatch.devices.core.sync.AutoSyncInterval
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.features.device.DevicePanelActions
import tech.mmarca.openwatch.features.device.DeviceScreenHost
import tech.mmarca.openwatch.features.watches.generic.GenericDevicePanelRoute
import tech.mmarca.openwatch.ui.components.ConfirmRemoveDialog
import tech.mmarca.openwatch.ui.components.DeclareAppBar
import tech.mmarca.openwatch.ui.components.DeviceAvatar
import tech.mmarca.openwatch.ui.components.DeviceRowIconGap
import tech.mmarca.openwatch.ui.components.InlineGrantPrompt
import tech.mmarca.openwatch.ui.components.OpenWatchCard
import tech.mmarca.openwatch.ui.components.OpenWatchIconButton
import tech.mmarca.openwatch.ui.components.RenameDialog
import tech.mmarca.openwatch.ui.components.ScreenAppBar
import tech.mmarca.openwatch.ui.components.ScreenColumn
import tech.mmarca.openwatch.ui.components.SectionHeader
import tech.mmarca.openwatch.ui.components.SwitchCard
import tech.mmarca.openwatch.ui.components.formatDeviceTime
import tech.mmarca.openwatch.ui.components.rememberHealthConnectPermissionLauncher
import tech.mmarca.openwatch.ui.theme.Spacing

/**
 * One device's destination: the only place that knows its ViewModel. It declares the app bar,
 * re-reads the background grant on resume, owns the Health Connect requests (the background
 * read, and the grants a sync left waiting) and goes back once the device is removed. The
 * device's family brings its own panel ([tech.mmarca.openwatch.features.device.DevicePanelContribution]),
 * with its own state holder, launchers and dialog, and opens its own screens through [host]; a
 * family with none gets the generic one, which draws what its contracts say (its live heart
 * rate, what it pushed that waits for Health Connect). [WatchDeviceScreen] draws.
 */
@Composable
fun WatchDeviceRoute(
    host: DeviceScreenHost,
    viewModel: WatchDeviceViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    DeclareAppBar(ScreenAppBar(title = state.device?.displayName))
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshBackgroundAccess() }
    val backgroundAccessLauncher = rememberHealthConnectPermissionLauncher(
        onResult = viewModel::refreshBackgroundAccess,
    )
    // A sync right after a grant writes what it now may, and replaces the card's "not written" line. Not after a
    // refusal: that would hold the watch link for a run that ends where it began.
    val syncGrantLauncher = rememberHealthConnectPermissionLauncher(onGranted = viewModel::syncNow)
    LaunchedEffect(state.permissionRequest) {
        val request = state.permissionRequest ?: return@LaunchedEffect
        backgroundAccessLauncher.launch(request.permissions)
        viewModel.onPermissionRequestHandled()
    }

    val screen: @Composable (WatchDevicePanel) -> Unit = { panel ->
        WatchDeviceScreen(
            state = state,
            panel = panel,
            onRename = viewModel::rename,
            onDetectSensors = viewModel::detectBroadcastSensors,
            onSelectAutoSync = viewModel::selectAutoSync,
            onAllowBackgroundRead = viewModel::allowBackgroundRead,
            onEnabledChange = viewModel::setEnabled,
            onRemove = { deleteWatchHistory ->
                viewModel.removeDevice(deleteWatchHistory)
                host.onRemoved()
            },
        )
    }
    val device = state.device
    val panel = state.family?.let(viewModel::panelFor)
    when {
        device == null -> screen(WatchDevicePanel.None)
        panel != null -> panel.Panel(
            device = device,
            sync = state.sync,
            actions = DevicePanelActions(
                onSync = viewModel::syncNow,
                onAllowSyncGrants = syncGrantLauncher.launch,
                host = host,
            ),
            content = screen,
        )
        else -> GenericDevicePanelRoute(features = state.features, content = screen)
    }
}

/**
 * What a device's family adds to the device view, in the three places it goes, so the cards
 * every device has keep one order: [afterHeader] (its status, actions and the rows that open
 * other screens), [afterLiveSensors] (what it shows last before the Device section) and
 * [afterSchedule] (its switches, in the Device section).
 */
@Immutable
class WatchDevicePanel(
    /** The header says "Never synced" before a first sync; otherwise it names the device, whose panel words the sync. */
    val saysNeverSynced: Boolean = false,
    val afterHeader: @Composable () -> Unit = {},
    val afterLiveSensors: @Composable () -> Unit = {},
    val afterSchedule: @Composable () -> Unit = {},
) {
    companion object {
        /** A device no family claims: the shared cards alone. */
        val None = WatchDevicePanel()
    }
}

/**
 * One device, and everything about it. Order: status, actions, configuration,
 * with removal at the bottom where a mis-tap cannot reach it.
 */
@Composable
fun WatchDeviceScreen(
    state: WatchDeviceUiState,
    panel: WatchDevicePanel,
    onRename: (String) -> Unit,
    onDetectSensors: () -> Unit,
    onSelectAutoSync: (AutoSyncInterval) -> Unit,
    onAllowBackgroundRead: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onRemove: (deleteWatchHistory: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val device = state.device
    if (device == null) {
        // Removed while this screen was open, or opened from a stale entry.
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_data))
        }
        return
    }

    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showRemoveDialog by rememberSaveable { mutableStateOf(false) }

    WatchDeviceContent(
        device = device,
        state = state,
        panel = panel,
        onRename = { showRenameDialog = true },
        onDetectSensors = onDetectSensors,
        onSelectAutoSync = onSelectAutoSync,
        onAllowBackgroundRead = onAllowBackgroundRead,
        onEnabledChange = onEnabledChange,
        onRemove = { showRemoveDialog = true },
        modifier = modifier,
    )

    if (showRenameDialog) {
        RenameDialog(
            title = stringResource(R.string.settings_watch_rename),
            initialName = device.displayName,
            onSave = { name ->
                onRename(name)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
        )
    }
    if (showRemoveDialog) {
        ConfirmRemoveDialog(
            title = stringResource(R.string.settings_device_remove_confirm_title, device.displayName),
            body = stringResource(R.string.settings_watch_remove_confirm_body),
            // The last Garmin watch's watch-only history has no other owner left.
            option = stringResource(R.string.settings_watch_remove_delete_history).takeIf { state.offersHistoryDeletion },
            onConfirm = { deleteWatchHistory ->
                showRemoveDialog = false
                onRemove(deleteWatchHistory)
            },
            onDismiss = { showRemoveDialog = false },
        )
    }
}

/** The header, the family's panel where it goes, the live sensors, the schedule, and the device itself. */
@Composable
private fun WatchDeviceContent(
    device: BleSensorDevice,
    state: WatchDeviceUiState,
    panel: WatchDevicePanel,
    onRename: () -> Unit,
    onDetectSensors: () -> Unit,
    onSelectAutoSync: (AutoSyncInterval) -> Unit,
    onAllowBackgroundRead: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalPadding = Spacing.lg,
    ) {
        StatusCard(device = device, saysNeverSynced = panel.saysNeverSynced, onRename = onRename)
        panel.afterHeader()

        // A bike computer can broadcast BLE sensors. Opt-in here, since broadcast
        // mode must be detected while the device is live. A sensor is one already: its
        // family streams it.
        if (device.isLiveSensorCapable && device.kind != BleDeviceKind.SENSOR) {
            SectionHeader(stringResource(R.string.settings_bike_live_sensor_section))
            LiveSensorSection(
                device = device,
                isDetecting = state.isDetectingSensors,
                foundNothing = state.detectFoundNothing,
                onDetect = onDetectSensors,
            )
        }
        panel.afterLiveSensors()

        SectionHeader(stringResource(R.string.settings_watch_section_device))
        // First: the schedule decides whether this screen is ever visited again.
        // Anything that syncs syncs on a schedule too.
        if (DeviceFeature.SYNC in state.features) {
            AutoSyncCard(
                selected = state.autoSync,
                backgroundReadMissing = state.backgroundReadMissing,
                onSelect = onSelectAutoSync,
                onAllowBackgroundRead = onAllowBackgroundRead,
            )
        }
        panel.afterSchedule()
        SwitchCard(
            title = stringResource(R.string.settings_watch_enabled),
            checked = device.enabled,
            onCheckedChange = onEnabledChange,
        )
        // Last, and its own card: a destructive action wants distance.
        OpenWatchCard {
            Text(
                text = stringResource(R.string.settings_watch_remove),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onRemove)
                    .padding(Spacing.lg),
            )
        }
    }
}

@Composable
private fun StatusCard(
    device: BleSensorDevice,
    saysNeverSynced: Boolean,
    onRename: () -> Unit,
) {
    // A device never synced is named, unless its panel leaves the sync line to the header;
    // synced, every device reads the same.
    val syncedAt = device.lastSyncedAt
    val statusLine = when {
        syncedAt != null -> stringResource(R.string.settings_watch_last_synced, formatDeviceTime(syncedAt))
        saysNeverSynced -> stringResource(R.string.settings_watch_never_synced)
        else -> device.bluetoothName ?: device.address
    }

    OpenWatchCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
        ) {
            DeviceAvatar(
                size = 44,
                icon = if (device.isBikeComputer) Icons.AutoMirrored.Outlined.DirectionsBike else null,
            )
            Spacer(modifier = Modifier.width(DeviceRowIconGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (device.enabled) {
                        stringResource(R.string.settings_watch_connected)
                    } else {
                        stringResource(R.string.settings_watch_not_connected)
                    },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Text(
                    text = statusLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            device.batteryPercent?.let { battery ->
                Spacer(modifier = Modifier.width(Spacing.md))
                Text(
                    text = formatPercent(battery),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
            OpenWatchIconButton(onClick = onRename) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.settings_watch_rename),
                )
            }
        }
    }
}

/**
 * How often the watch syncs on its own. A segmented row, so the choice in
 * force is visible at a glance. The body says the watch must be in range and
 * Android picks the moment, or a fifteen-minute drift reads as broken.
 */
@Composable
private fun AutoSyncCard(
    selected: AutoSyncInterval,
    backgroundReadMissing: Boolean,
    onSelect: (AutoSyncInterval) -> Unit,
    onAllowBackgroundRead: () -> Unit,
) {
    OpenWatchCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.settings_watch_auto_sync),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.settings_watch_auto_sync_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm),
            ) {
                AutoSyncInterval.entries.forEachIndexed { index, interval ->
                    SegmentedButton(
                        selected = selected == interval,
                        onClick = { onSelect(interval) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = AutoSyncInterval.entries.size,
                        ),
                        label = {
                            Text(
                                text = stringResource(
                                    when (interval) {
                                        AutoSyncInterval.OFF ->
                                            R.string.settings_watch_auto_sync_off
                                        AutoSyncInterval.EVERY_30_MINUTES ->
                                            R.string.settings_watch_auto_sync_30m
                                        AutoSyncInterval.HOURLY ->
                                            R.string.settings_watch_auto_sync_1h
                                        AutoSyncInterval.EVERY_2_HOURS ->
                                            R.string.settings_watch_auto_sync_2h
                                    },
                                ),
                            )
                        },
                    )
                }
            }
            if (selected != AutoSyncInterval.OFF && backgroundReadMissing) {
                InlineGrantPrompt(
                    message = stringResource(R.string.settings_watch_auto_sync_background_access),
                    actionLabel = stringResource(R.string.settings_watch_auto_sync_background_access_action),
                    onGrant = onAllowBackgroundRead,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }
        }
    }
}

/**
 * The BLE broadcast controls for a bike computer: its capabilities, and a redetect button. Public
 * for the app's instrumented test of the device screen's rows.
 */
@Composable
fun LiveSensorSection(
    device: BleSensorDevice,
    isDetecting: Boolean,
    foundNothing: Boolean,
    onDetect: () -> Unit,
) {
    OpenWatchCard {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Text(
                text = stringResource(R.string.settings_bike_live_sensor_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (device.capabilities.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.padding(top = Spacing.md),
                ) {
                    device.capabilities.forEach { capability ->
                        AssistChip(
                            onClick = {},
                            label = { Text(capabilityLabel(capability)) },
                        )
                    }
                }
            }
            if (foundNothing) {
                Text(
                    text = stringResource(R.string.settings_bike_no_broadcast),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }
            OutlinedButton(
                onClick = onDetect,
                enabled = !isDetecting,
                modifier = Modifier.padding(top = Spacing.sm),
            ) {
                if (isDetecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Spacing.lg),
                        strokeWidth = WatchProgressStroke,
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.BluetoothSearching,
                        contentDescription = null,
                        modifier = Modifier.size(WatchInlineIconSize),
                    )
                }
                Text(
                    text = if (isDetecting) {
                        stringResource(R.string.settings_bike_detecting)
                    } else {
                        stringResource(R.string.settings_bike_detect_sensors)
                    },
                    modifier = Modifier.padding(start = Spacing.sm),
                )
            }
        }
    }
}

/** Inline status icons, between bodySmall text sizes. */
private val WatchInlineIconSize = 18.dp
/** The small in-row progress spinner's stroke. */
private val WatchProgressStroke = 2.dp
