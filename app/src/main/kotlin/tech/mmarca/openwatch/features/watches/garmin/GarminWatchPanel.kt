package tech.mmarca.openwatch.features.watches.garmin

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Duration
import java.time.Instant
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.features.device.R as DeviceR
import tech.mmarca.openwatch.core.presentation.resolve
import tech.mmarca.openwatch.devices.core.DeviceFeature
import tech.mmarca.openwatch.devices.core.sync.AutoSyncInterval
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncUiState
import tech.mmarca.openwatch.devices.garmin.GarminAgpsFileState
import tech.mmarca.openwatch.devices.garmin.GarminAgpsKind
import tech.mmarca.openwatch.devices.garmin.GarminCapability
import tech.mmarca.openwatch.devices.garmin.GarminSettingsService
import tech.mmarca.openwatch.devices.garmin.canStorePoints
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorCapability
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration
import tech.mmarca.openwatch.features.watches.NavigationRow
import tech.mmarca.openwatch.features.watches.NotificationAccessDisclosureDialog
import tech.mmarca.openwatch.features.watches.SyncOutcomeSummary
import tech.mmarca.openwatch.features.watches.WatchAction
import tech.mmarca.openwatch.features.watches.WatchDevicePanel
import tech.mmarca.openwatch.features.watches.WatchDeviceScreen
import tech.mmarca.openwatch.features.watches.WatchDeviceUiState
import tech.mmarca.openwatch.features.watches.toScreenError
import tech.mmarca.openwatch.ui.components.OpenWatchCard
import tech.mmarca.openwatch.ui.components.SectionHeader
import tech.mmarca.openwatch.ui.components.SwitchCard
import tech.mmarca.openwatch.ui.components.SwitchRow
import tech.mmarca.openwatch.ui.components.rememberRuntimePermissionRequester
import tech.mmarca.openwatch.ui.theme.OpenWatchTheme
import tech.mmarca.openwatch.ui.theme.Spacing

/** The watch's settings tree browser exists, so the rows that open it are live. */
internal const val WatchSettingsTreeAvailable: Boolean = true

/** The root of the watch's settings tree. */
internal const val WatchSettingsRootScreenId: Int = GarminSettingsService.ROOT_SCREEN_ID

/** The alarms screen's well-known id. */
internal const val WatchSettingsAlarmsScreenId: Int = GarminSettingsService.ALARMS_SCREEN_ID

/**
 * The Garmin half of the device destination: the only place that knows the panel's ViewModel.
 * It re-reads the grants on resume, owns the runtime-grant requests and the ephemeris picker,
 * shows the music disclosure, and draws [content] with the panel.
 */
@Composable
fun GarminWatchPanelRoute(
    device: BleSensorDevice,
    sync: DeviceSyncUiState,
    onSync: () -> Unit,
    onAllowSyncGrants: (Set<String>) -> Unit,
    onOpenData: (String) -> Unit,
    onOpenNotifications: (String) -> Unit,
    onOpenWatchSettings: (String, Int) -> Unit,
    onOpenSendPoint: (String) -> Unit,
    onOpenAlarms: (String) -> Unit,
    viewModel: GarminWatchPanelViewModel = hiltViewModel(),
    content: @Composable (WatchDevicePanel) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Notification access is granted in Android's settings, so it is re-read on the way back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumed() }
    val runtimePermissions = rememberRuntimePermissionRequester { viewModel.onRuntimePermissionAnswered() }
    LaunchedEffect(state.permissionRequest) {
        val request = state.permissionRequest ?: return@LaunchedEffect
        runtimePermissions.request(listOf(request.permission))
        viewModel.onPermissionRequestHandled()
    }
    val ephemerisPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importAgps) }

    if (state.showMusicDisclosure) {
        NotificationAccessDisclosureDialog(
            title = stringResource(R.string.settings_watch_music_disclosure_title),
            body = stringResource(R.string.settings_watch_music_disclosure_body),
            onAccept = viewModel::acceptMusicDisclosure,
            onDecline = viewModel::declineMusicDisclosure,
        )
    }

    content(
        garminWatchPanel(
            device = device,
            state = state,
            sync = sync,
            actions = GarminWatchPanelActions(
                onOpenData = { onOpenData(device.id) },
                onSync = onSync,
                onAllowSyncGrants = onAllowSyncGrants,
                onOpenAlarms = {
                    when (state.alarmsDestination) {
                        AlarmsDestination.SETTINGS_TREE -> onOpenWatchSettings(device.id, WatchSettingsAlarmsScreenId)
                        AlarmsDestination.PHONE_LIST -> onOpenAlarms(device.id)
                    }
                },
                onToggleFind = viewModel::toggleFind,
                onOpenWatchSettings = { onOpenWatchSettings(device.id, WatchSettingsRootScreenId) },
                onOpenNotifications = { onOpenNotifications(device.id) },
                onOpenSendPoint = { onOpenSendPoint(device.id) },
                onStayConnectedChange = viewModel::setStayConnected,
                onLiveReadingsChange = viewModel::setLiveReadings,
                onCalendarSyncChange = viewModel::toggleCalendarSync,
                onMusicControlsChange = viewModel::setMusicControls,
                onNavigationOnWatchChange = viewModel::toggleNavigationOnWatch,
                onHighMtuChange = viewModel::setHighMtu,
                onImportEphemeris = { ephemerisPicker.launch(arrayOf("*/*")) },
                onForgetEphemeris = viewModel::forgetAgps,
            ),
        ),
    )
}

/** What the Garmin panel's controls do. Each does nothing by default, for a preview. */
@Immutable
class GarminWatchPanelActions(
    val onOpenData: () -> Unit = {},
    val onSync: () -> Unit = {},
    val onAllowSyncGrants: (Set<String>) -> Unit = {},
    val onOpenAlarms: () -> Unit = {},
    val onToggleFind: () -> Unit = {},
    val onOpenWatchSettings: () -> Unit = {},
    val onOpenNotifications: () -> Unit = {},
    val onOpenSendPoint: () -> Unit = {},
    val onStayConnectedChange: (Boolean) -> Unit = {},
    val onLiveReadingsChange: (Boolean) -> Unit = {},
    val onCalendarSyncChange: (Boolean) -> Unit = {},
    val onMusicControlsChange: (Boolean) -> Unit = {},
    val onNavigationOnWatchChange: (Boolean) -> Unit = {},
    val onHighMtuChange: (Boolean) -> Unit = {},
    val onImportEphemeris: () -> Unit = {},
    val onForgetEphemeris: (GarminAgpsKind) -> Unit = {},
)

/**
 * A Garmin device's panel: under the header its last sync, its actions and the rows that lead to
 * its other screens; in the Device section its switches. Only a Garmin GFDI device has sync,
 * settings and find; the header carries its sync line.
 */
internal fun garminWatchPanel(
    device: BleSensorDevice,
    state: GarminWatchPanelUiState,
    sync: DeviceSyncUiState,
    actions: GarminWatchPanelActions,
): WatchDevicePanel = WatchDevicePanel(
    saysNeverSynced = true,
    afterHeader = { GarminWatchStatus(device = device, state = state, sync = sync, actions = actions) },
    afterSchedule = { GarminWatchSwitches(state = state, actions = actions) },
)

/** The last sync's outcome, the action band and what it says, then the rows into the watch's other screens. */
@Composable
private fun GarminWatchStatus(
    device: BleSensorDevice,
    state: GarminWatchPanelUiState,
    sync: DeviceSyncUiState,
    actions: GarminWatchPanelActions,
) {
    sync.lastFor(device.id)?.let { SyncOutcomeSummary(it, onAllowGrants = actions.onAllowSyncGrants) }
    ActionsRow(
        device = device,
        state = state,
        sync = sync,
        // A bike computer records rides, not wrist wellness; its Data view is hidden.
        isBikeComputer = device.isBikeComputer,
        onOpenData = actions.onOpenData,
        onSync = actions.onSync,
        onOpenAlarms = actions.onOpenAlarms,
        onToggleFind = actions.onToggleFind,
    )
    val finding = state.find.isFindingDevice(device.id)
    if (finding || state.find.findFailed) {
        Text(
            text = if (finding) {
                stringResource(R.string.settings_watch_find_ringing)
            } else {
                stringResource(R.string.settings_watch_find_failed)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (state.find.findFailed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.padding(horizontal = Spacing.lg),
        )
    }
    sync.failureFor(device.id)?.toScreenError().resolve()?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = Spacing.lg),
        )
    }

    // Only for a watch that has a settings tree.
    if (state.hasSettingsTree) {
        SectionHeader(stringResource(R.string.settings_watch_settings_section))
        OnDeviceSettingsRow(onOpen = actions.onOpenWatchSettings)
    }
    // `supports` treats an unknown capability as present, so an unsynced watch gets the row.
    if (state.supports(GarminCapability.GNCS)) {
        SectionHeader(stringResource(R.string.settings_watch_notifications_section))
        NotificationsRow(onOpen = actions.onOpenNotifications)
    }
    // The watch's answer to the upload is the real gate; this only hides the obvious no.
    if (state.capabilities.canStorePoints()) {
        SendPointRow(onOpen = actions.onOpenSendPoint)
    }
}

/** The watch's switches, under the schedule: the held link and what rides it, then the link itself and the ephemeris. */
@Composable
private fun GarminWatchSwitches(state: GarminWatchPanelUiState, actions: GarminWatchPanelActions) {
    SwitchCard(
        title = stringResource(R.string.settings_watch_stay_connected),
        body = stringResource(R.string.settings_watch_stay_connected_body),
        checked = state.stayConnected,
        onCheckedChange = actions.onStayConnectedChange,
    )
    // Only with the link held: a stream needs a connection.
    if (state.stayConnected) {
        SwitchCard(
            title = stringResource(R.string.settings_watch_live_readings),
            body = state.liveHeartRateBpm
                ?.let { stringResource(DeviceR.string.settings_watch_live_now, it) }
                ?: stringResource(R.string.settings_watch_live_readings_body),
            checked = state.liveReadings,
            onCheckedChange = actions.onLiveReadingsChange,
        )
    }
    CalendarSyncCard(state = state, onToggle = actions.onCalendarSyncChange)
    MusicControlsCard(state = state, onToggle = actions.onMusicControlsChange)
    NavigationOnWatchCard(state = state, onToggle = actions.onNavigationOnWatchChange)
    HighMtuCard(state = state, onToggle = actions.onHighMtuChange)
    EphemerisCard(state = state, onImport = actions.onImportEphemeris, onForget = actions.onForgetEphemeris)
}

@Composable
internal fun ActionsRow(
    device: BleSensorDevice,
    state: GarminWatchPanelUiState,
    sync: DeviceSyncUiState,
    isBikeComputer: Boolean,
    onOpenData: () -> Unit,
    onSync: () -> Unit,
    onOpenAlarms: () -> Unit,
    onToggleFind: () -> Unit,
) {
    val finding = state.find.isFindingDevice(device.id)
    // One radio per watch: its sync and a find cannot overlap. Stopping this find stays available.
    val busy = (sync.isSyncingDevice(device.id) || state.find.findingDeviceId != null) && !finding
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
    ) {
        if (!isBikeComputer) {
            WatchAction(
                icon = Icons.Outlined.Insights,
                label = stringResource(R.string.settings_watch_action_data),
                enabled = true,
                onClick = onOpenData,
            )
        }
        WatchAction(
            icon = Icons.Outlined.Sync,
            label = stringResource(R.string.settings_watch_action_sync),
            busy = sync.isSyncingDevice(device.id),
            // Disabled while this watch syncs; another one may sync beside it.
            enabled = !busy && !finding,
            onClick = onSync,
        )
        // A screen in the settings tree at a well-known id. A watch with no tree keeps
        // its alarms on the phone; a bike computer has none.
        if (state.hasSettingsTree || !isBikeComputer) {
            WatchAction(
                icon = Icons.Outlined.Alarm,
                label = stringResource(R.string.settings_watch_action_alarms),
                enabled = !busy,
                onClick = onOpenAlarms,
            )
        }
        if (state.supports(GarminCapability.FIND_MY_WATCH)) {
            // A toggle: the watch alerts for a minute unless stopped, so the same control stops it.
            WatchAction(
                icon = if (finding) Icons.Outlined.Stop else Icons.Outlined.WifiTethering,
                label = if (finding) {
                    stringResource(R.string.settings_watch_find_stop)
                } else {
                    stringResource(R.string.settings_watch_action_find)
                },
                enabled = !busy,
                onClick = onToggleFind,
            )
        }
    }
}

/** The watch's music controls. Off by default; they need Android's notification access. */
@Composable
private fun MusicControlsCard(
    state: GarminWatchPanelUiState,
    onToggle: (Boolean) -> Unit,
) {
    OpenWatchCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.sm),
        ) {
            SwitchRow(
                title = stringResource(R.string.settings_watch_music),
                body = stringResource(R.string.settings_watch_music_body),
                checked = state.musicControls,
                onCheckedChange = onToggle,
                contentPadding = PaddingValues(horizontal = Spacing.lg),
            )
            if (state.musicAccessMissing) {
                Text(
                    text = stringResource(R.string.settings_watch_music_no_access),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
        }
    }
}

/** The watch's calendar glance. Off by default; asks for the OS permission when switched on. */
@Composable
private fun CalendarSyncCard(
    state: GarminWatchPanelUiState,
    onToggle: (Boolean) -> Unit,
) {
    OpenWatchCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.sm),
        ) {
            SwitchRow(
                title = stringResource(R.string.settings_watch_calendar),
                body = stringResource(R.string.settings_watch_calendar_body),
                checked = state.calendarSync,
                onCheckedChange = onToggle,
                contentPadding = PaddingValues(horizontal = Spacing.lg),
            )
            if (state.calendarPermissionMissing) {
                Text(
                    text = stringResource(R.string.settings_watch_calendar_no_permission),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
        }
    }
}

/**
 * CoMaps guidance on the wrist, over the notification link: one
 * notification, updated in place. Off by default and complete in itself.
 */
@Composable
private fun NavigationOnWatchCard(
    state: GarminWatchPanelUiState,
    onToggle: (Boolean) -> Unit,
) {
    OpenWatchCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.sm),
        ) {
            SwitchRow(
                title = stringResource(R.string.settings_watch_navigation),
                body = stringResource(R.string.settings_watch_navigation_body),
                checked = state.navigationOnWatch,
                onCheckedChange = onToggle,
                contentPadding = PaddingValues(horizontal = Spacing.lg),
            )
            if (state.coMapsPermissionMissing) {
                Text(
                    text = stringResource(R.string.settings_watch_navigation_no_permission),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
        }
    }
}

/**
 * The large-Bluetooth-packets switch: on by default, off for a watch that
 * drops the link or never gets a sync going once the packets grow.
 */
@Composable
private fun HighMtuCard(
    state: GarminWatchPanelUiState,
    onToggle: (Boolean) -> Unit,
) {
    SwitchCard(
        title = stringResource(R.string.settings_watch_high_mtu),
        body = stringResource(R.string.settings_watch_high_mtu_body),
        checked = state.highMtu,
        onCheckedChange = onToggle,
    )
}

/**
 * GPS ephemeris, which turns a two-minute cold fix into seconds. This app has
 * no INTERNET permission, so the user supplies the file. The URL the watch
 * asked for says which file this chipset wants.
 */
@Composable
private fun EphemerisCard(
    state: GarminWatchPanelUiState,
    onImport: () -> Unit,
    onForget: (GarminAgpsKind) -> Unit,
) {
    val file = state.agps.files.firstOrNull()
    OpenWatchCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.settings_watch_agps),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onImport) {
                    Text(
                        stringResource(
                            if (file == null) {
                                R.string.settings_watch_agps_import
                            } else {
                                R.string.settings_watch_agps_replace
                            },
                        ),
                    )
                }
            }
            Text(
                text = ephemerisSummary(file),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (file?.problem != null) {
                Text(
                    text = stringResource(R.string.settings_watch_agps_unusable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.agpsMessage?.let { message ->
                Text(
                    text = stringResource(message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            state.agps.requestedUrls.lastOrNull()?.let { url ->
                Text(
                    text = stringResource(R.string.settings_watch_agps_asked_for, url),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (file != null) {
                TextButton(onClick = { onForget(file.kind) }) {
                    Text(
                        text = stringResource(R.string.settings_watch_agps_remove),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** What the card says under its title: the file, or the offer of one. */
@Composable
private fun ephemerisSummary(file: GarminAgpsFileState?): String {
    if (file == null) return stringResource(R.string.settings_watch_agps_body)
    val imported = DateUtils.getRelativeTimeSpanString(
        file.importedAt.toEpochMilli(),
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
    val kind = stringResource(
        when (file.kind) {
            GarminAgpsKind.CONSTELLATION_TAR -> R.string.settings_watch_agps_kind_tar
            GarminAgpsKind.RX_NETWORKS -> R.string.settings_watch_agps_kind_rx
            GarminAgpsKind.SONY_CPE -> R.string.settings_watch_agps_kind_sony
        },
    )
    val served = file.lastServedAt?.let {
        DateUtils.getRelativeTimeSpanString(
            it.toEpochMilli(),
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    }
    val detail = if (served == null) {
        stringResource(R.string.settings_watch_agps_never_taken, imported)
    } else {
        stringResource(R.string.settings_watch_agps_taken, imported, served)
    }
    return "$kind · $detail"
}

/** The way into the watch's own settings tree. */
@Composable
internal fun OnDeviceSettingsRow(onOpen: () -> Unit) {
    NavigationRow(Icons.Outlined.BrightnessMedium, R.string.settings_watch_on_device_settings, R.string.settings_watch_on_device_settings_body, onOpen)
}

/** The way to the send-a-point form. */
@Composable
private fun SendPointRow(onOpen: () -> Unit) {
    NavigationRow(Icons.Outlined.Place, R.string.settings_watch_point_row, R.string.settings_watch_point_row_body, onOpen)
}

/** Mirrors phone notifications to the watch. The switches live on the notifications screen. */
@Composable
private fun NotificationsRow(onOpen: () -> Unit) {
    NavigationRow(Icons.Outlined.NotificationsActive, R.string.settings_watch_notifications_title, R.string.settings_watch_notifications_body, onOpen)
}

@Preview(showBackground = true)
@Composable
private fun GarminWatchPreview() {
    val watch = previewGarminDevice(BleDeviceKind.WATCH, "vívoactive 5", capabilities = emptySet())
    GarminDevicePreview(
        device = watch,
        panel = GarminWatchPanelUiState(
            capabilities = setOf(
                GarminCapability.SYNC,
                GarminCapability.GNCS,
                GarminCapability.FIND_MY_WATCH,
                GarminCapability.REALTIME_SETTINGS,
                GarminCapability.WAYPOINT_TRANSFER,
            ),
            stayConnected = true,
            liveReadings = true,
            liveHeartRateBpm = 62,
            calendarSync = true,
            calendarPermissionMissing = true,
        ),
    )
}

/** An Edge: it syncs and broadcasts, and has no wellness Data view and no alarms. */
@Preview(showBackground = true)
@Composable
private fun GarminBikeComputerPreview() {
    val edge = previewGarminDevice(
        BleDeviceKind.BIKE_COMPUTER,
        "Edge 840",
        capabilities = setOf(BleSensorCapability.CYCLING_SPEED_DISTANCE, BleSensorCapability.CYCLING_CADENCE),
    )
    GarminDevicePreview(
        device = edge,
        panel = GarminWatchPanelUiState(capabilities = setOf(GarminCapability.SYNC)),
    )
}

@Composable
private fun GarminDevicePreview(device: BleSensorDevice, panel: GarminWatchPanelUiState) {
    OpenWatchTheme {
        WatchDeviceScreen(
            state = WatchDeviceUiState(
                device = device,
                family = DeviceIntegration.GARMIN,
                features = setOf(DeviceFeature.SYNC, DeviceFeature.FIND),
                autoSync = AutoSyncInterval.HOURLY,
            ),
            panel = garminWatchPanel(device, panel, DeviceSyncUiState(), GarminWatchPanelActions()),
            onRename = {},
            onDetectSensors = {},
            onSelectAutoSync = {},
            onAllowBackgroundRead = {},
            onEnabledChange = {},
            onRemove = {},
        )
    }
}

private fun previewGarminDevice(kind: BleDeviceKind, name: String, capabilities: Set<BleSensorCapability>) = BleSensorDevice(
    id = "garmin-1",
    displayName = name,
    address = "E0:48:24:D5:F7:10",
    bluetoothName = name,
    capabilities = capabilities,
    enabled = true,
    wheelCircumferenceMm = null,
    batteryPercent = 78,
    addedAt = Instant.parse("2026-06-23T08:00:00Z"),
    kind = kind,
    integration = DeviceIntegration.GARMIN,
    lastSyncedAt = Instant.now().minus(Duration.ofHours(2)),
)
