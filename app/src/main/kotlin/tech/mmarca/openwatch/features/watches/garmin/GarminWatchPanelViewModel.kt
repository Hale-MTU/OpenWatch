package tech.mmarca.openwatch.features.watches.garmin

import android.Manifest
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.core.performance.DispatcherProvider
import tech.mmarca.openwatch.devices.garmin.GarminAgpsImport
import tech.mmarca.openwatch.devices.garmin.GarminAgpsKind
import tech.mmarca.openwatch.devices.garmin.GarminAgpsState
import tech.mmarca.openwatch.devices.garmin.GarminAgpsStore
import tech.mmarca.openwatch.devices.garmin.GarminCapability
import tech.mmarca.openwatch.devices.garmin.GarminWatchOptions
import tech.mmarca.openwatch.features.watches.GarminWatchActionsController
import tech.mmarca.openwatch.features.watches.WatchFindUiState
import tech.mmarca.openwatch.features.watches.WatchNotificationsGateway
import tech.mmarca.openwatch.features.watches.WatchPermissionRequest
import tech.mmarca.openwatch.navigation.DeviceDetail

/** What the Garmin panel of the device view shows: the actions, the rows, the switches. */
@Immutable
data class GarminWatchPanelUiState(
    /** What the watch declared in its last handshake. Empty means unknown, not none; see [supports]. */
    val capabilities: Set<GarminCapability> = emptySet(),
    val find: WatchFindUiState = WatchFindUiState(),
    /** Companion mode: hold the link whenever the watch is in range. */
    val stayConnected: Boolean = false,
    /** Links ask the watch for large Bluetooth packets. Off is the workaround for a watch that misbehaves with them. */
    val highMtu: Boolean = true,
    /** Live readings streamed over that link. */
    val liveReadings: Boolean = false,
    /** The most recent live heart rate, while one is arriving. */
    val liveHeartRateBpm: Int? = null,
    /** The watch's music controls drive the phone's player. Off by default. */
    val musicControls: Boolean = false,
    /** Music controls are on but Android's notification access is off. */
    val musicAccessMissing: Boolean = false,
    /** The disclosure that comes before the trip to Android's settings. */
    val showMusicDisclosure: Boolean = false,
    /** The watch may read the phone's calendar. Off by default. */
    val calendarSync: Boolean = false,
    /** Calendar sync is on but the OS permission has been revoked. */
    val calendarPermissionMissing: Boolean = false,
    /** Live CoMaps guidance is shown on the watch, recording or not. */
    val navigationOnWatch: Boolean = false,
    /** Guidance on the watch is on but CoMaps' own permission has been declined. */
    val coMapsPermissionMissing: Boolean = false,
    /** GPS ephemeris the user supplied, and what the watch has asked for. */
    val agps: GarminAgpsState = GarminAgpsState(),
    /** The result of the last ephemeris import, until the next one. */
    @StringRes val agpsMessage: Int? = null,
    /** A grant for the screen to ask for, once; held until [GarminWatchPanelViewModel.onPermissionRequestHandled]. */
    val permissionRequest: WatchPermissionRequest.Runtime? = null,
) {
    /** Whether the watch declared [capability]. Unknown means show: a never-synced watch has no list. */
    fun supports(capability: GarminCapability): Boolean =
        capabilities.isEmpty() || capability in capabilities

    /** Whether the watch serves its settings, alarms included, as a tree of screens. */
    val hasSettingsTree: Boolean
        get() = WatchSettingsTreeAvailable && supports(GarminCapability.REALTIME_SETTINGS)

    /** Where the alarms action leads: the watch's own alarms screen when it has a settings tree, else the phone's list. */
    val alarmsDestination: AlarmsDestination
        get() = if (hasSettingsTree) AlarmsDestination.SETTINGS_TREE else AlarmsDestination.PHONE_LIST
}

/** Where the device view's alarms action leads. */
enum class AlarmsDestination { SETTINGS_TREE, PHONE_LIST }

/** One Garmin watch's panel: its find action, its switches, its ephemeris and the grants they ask for. */
@HiltViewModel
class GarminWatchPanelViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val options: GarminWatchOptions,
    private val agpsStore: GarminAgpsStore,
    private val actionsController: GarminWatchActionsController,
    private val notificationsGateway: WatchNotificationsGateway,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    val deviceId: String = savedStateHandle.toRoute<DeviceDetail>().deviceId

    /** The grants the switches' lines depend on, read on the way in, on resume and after each dialog. */
    private data class Grants(val notificationAccess: Boolean, val calendar: Boolean, val coMaps: Boolean)

    /** What only this screen knows: the grants as last read, the disclosure, the last import, the request outstanding. */
    private data class Local(
        val grants: Grants,
        val showMusicDisclosure: Boolean = false,
        @StringRes val agpsMessage: Int? = null,
        val permissionRequest: WatchPermissionRequest.Runtime? = null,
    )

    private val localState = MutableStateFlow(Local(grants = readGrants()))

    val uiState: StateFlow<GarminWatchPanelUiState> = combine(
        options.values(deviceId),
        options.liveHeartRate(deviceId),
        actionsController.state,
        agpsStore.agps,
        localState,
    ) { values, liveHeartRate, find, agps, local ->
        GarminWatchPanelUiState(
            capabilities = values.capabilities,
            find = find,
            stayConnected = values.stayConnected,
            highMtu = values.highMtu,
            liveReadings = values.liveReadings,
            liveHeartRateBpm = liveHeartRate,
            musicControls = values.musicControls,
            musicAccessMissing = values.musicControls && !local.grants.notificationAccess,
            showMusicDisclosure = local.showMusicDisclosure,
            calendarSync = values.calendarSync,
            calendarPermissionMissing = values.calendarSync && !local.grants.calendar,
            navigationOnWatch = values.navigationOnWatch,
            coMapsPermissionMissing = values.navigationOnWatch && !local.grants.coMaps,
            agps = agps,
            agpsMessage = local.agpsMessage,
            permissionRequest = local.permissionRequest,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, GarminWatchPanelUiState())

    /** Notification access is granted in Android's settings, so it and the other grants are read again on the way back. */
    fun onResumed() {
        options.refreshMusicAccess()
        refreshGrants()
    }

    /** Takes in an ephemeris file. Its contents say what it is; the message says why not. */
    fun importAgps(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(dispatchers.io) { agpsStore.import(uri) }
            localState.update {
                it.copy(
                    agpsMessage = when (result) {
                        is GarminAgpsImport.Imported -> R.string.settings_watch_agps_imported
                        GarminAgpsImport.NotEphemeris -> R.string.settings_watch_agps_not_ephemeris
                        GarminAgpsImport.Stale -> R.string.settings_watch_agps_stale
                        GarminAgpsImport.Unreadable -> R.string.settings_watch_agps_unreadable
                    },
                )
            }
        }
    }

    fun forgetAgps(kind: GarminAgpsKind) {
        agpsStore.forget(kind)
        localState.update { it.copy(agpsMessage = null) }
    }

    fun setStayConnected(enabled: Boolean) {
        options.setStayConnected(deviceId, enabled)
    }

    fun setLiveReadings(enabled: Boolean) {
        options.setLiveReadings(deviceId, enabled)
    }

    fun setHighMtu(enabled: Boolean) {
        options.setHighMtu(deviceId, enabled)
    }

    fun toggleFind() {
        actionsController.toggleFind(deviceId)
    }

    /**
     * Turns the watch's music controls on or off. Without notification
     * access, switching on shows the disclosure first and changes nothing yet.
     */
    fun setMusicControls(enabled: Boolean) {
        if (enabled && !notificationsGateway.isNotificationAccessGranted()) {
            localState.update { it.copy(showMusicDisclosure = true) }
            return
        }
        options.setMusicControls(deviceId, enabled)
    }

    /**
     * The user accepted the disclosure. The switch goes on and Android's
     * settings open; until access is granted the card says it is missing.
     */
    fun acceptMusicDisclosure() {
        localState.update { it.copy(showMusicDisclosure = false) }
        options.setMusicControls(deviceId, true)
        notificationsGateway.openNotificationAccessSettings()
    }

    fun declineMusicDisclosure() {
        localState.update { it.copy(showMusicDisclosure = false) }
    }

    /** The calendar switch. On asks for the calendar grant first; the switch is set once the dialog answers. */
    fun toggleCalendarSync(enabled: Boolean) {
        if (enabled) {
            requestRuntime(Manifest.permission.READ_CALENDAR, Purpose.CALENDAR)
        } else {
            options.setCalendarSync(deviceId, false)
        }
    }

    /**
     * Shows live CoMaps guidance on the watch. Works whether or not a session is running. On also
     * asks for CoMaps' own permission, named after the installed flavour, so a wearer who never
     * records can still grant it. The toggle sticks either way; without the grant the card says
     * what is missing.
     */
    fun toggleNavigationOnWatch(enabled: Boolean) {
        options.setNavigationOnWatch(deviceId, enabled)
        refreshGrants()
        // Null without a CoMaps installed: nothing to ask.
        if (enabled) {
            options.coMapsPermissionName()?.let { requestRuntime(it, Purpose.COMAPS) }
        }
    }

    /** The screen launched [GarminWatchPanelUiState.permissionRequest]; the answer comes to [onRuntimePermissionAnswered]. */
    fun onPermissionRequestHandled() {
        localState.update { it.copy(permissionRequest = null) }
    }

    /** The runtime dialog answered. What follows depends on what it was asked for, not on the answer. */
    fun onRuntimePermissionAnswered() {
        val purpose = savedStateHandle.remove<String>(PENDING_PERMISSION_PURPOSE_KEY)
            ?.let { name -> Purpose.entries.firstOrNull { it.name == name } }
            ?: return
        when (purpose) {
            // The toggle is set either way; without the grant the row says what is missing.
            Purpose.CALENDAR -> options.setCalendarSync(deviceId, true)
            // A refused observer stays refused, so the feed is opened again.
            Purpose.COMAPS -> options.onCoMapsPermissionAnswered()
        }
        refreshGrants()
    }

    /** The purpose is kept apart from the request, which the screen clears on launch, for the answer to find. */
    private fun requestRuntime(permission: String, purpose: Purpose) {
        savedStateHandle[PENDING_PERMISSION_PURPOSE_KEY] = purpose.name
        localState.update { it.copy(permissionRequest = WatchPermissionRequest.Runtime(permission)) }
    }

    private fun readGrants() = Grants(
        notificationAccess = notificationsGateway.isNotificationAccessGranted(),
        calendar = options.hasCalendarPermission(),
        coMaps = options.hasCoMapsPermission(),
    )

    private fun refreshGrants() {
        localState.update { it.copy(grants = readGrants()) }
    }

    /** What a runtime request was for. */
    private enum class Purpose { CALENDAR, COMAPS }
}

/** The purpose of the runtime request outstanding, kept through a process death so its answer still lands. */
private const val PENDING_PERMISSION_PURPOSE_KEY = "pendingPermissionPurpose"
