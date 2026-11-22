package tech.mmarca.openwatch.features.watches

import androidx.compose.runtime.Immutable
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import tech.mmarca.openwatch.core.performance.ApplicationScope
import tech.mmarca.openwatch.core.performance.DispatcherProvider
import tech.mmarca.openwatch.core.performance.runSuspendCatching
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.devices.core.DeviceFamilies
import tech.mmarca.openwatch.devices.core.DeviceFeature
import tech.mmarca.openwatch.devices.core.sync.AutoSyncInterval
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncController
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncUiState
import tech.mmarca.openwatch.devices.core.sync.WatchAutoSyncScheduler
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration
import tech.mmarca.openwatch.healthconnect.HealthConnectGateway
import tech.mmarca.openwatch.navigation.DeviceDetail
import tech.mmarca.openwatch.sensors.ble.BleSensorCoordinator

/** What the device view shows about any device: its header, its sync and schedule, its removal. */
@Immutable
data class WatchDeviceUiState(
    /** Null while loading and after removal — the screen shows its no-data state. */
    val device: BleSensorDevice? = null,
    /** The family that owns the device, which picks the panel below the shared cards; null for a plain sensor. */
    val family: DeviceIntegration? = null,
    /** What the device's family says it can do; empty for a plain sensor. */
    val features: Set<DeviceFeature> = emptySet(),
    val sync: DeviceSyncUiState = DeviceSyncUiState(),
    /** The bike computer's broadcast-sensor detection, in flight. */
    val isDetectingSensors: Boolean = false,
    /** The last detection connected and found nothing broadcasting. */
    val detectFoundNothing: Boolean = false,
    /** How often the watch syncs on its own, or OFF for by-hand only. */
    val autoSync: AutoSyncInterval = AutoSyncInterval.OFF,
    /**
     * Health Connect offers background access and the user has not granted it. A sync that
     * runs with the app closed then saves the watch's data, but nothing built from other
     * apps' records catches up until the app is opened.
     */
    val backgroundReadMissing: Boolean = false,
    /** Removing this device can delete history its family keeps; the dialog then offers it. */
    val offersHistoryDeletion: Boolean = false,
    /** A permission for the screen to ask for, once; held until [WatchDeviceViewModel.onPermissionRequestHandled]. */
    val permissionRequest: WatchPermissionRequest.HealthConnect? = null,
)

/** A permission the device view asks Android for on a state holder's behalf, held in its state until launched. */
sealed interface WatchPermissionRequest {
    /** Health Connect's own dialog; the background read is re-read once it closes. */
    data class HealthConnect(val permissions: Set<String>) : WatchPermissionRequest

    /** One runtime grant. Its answer goes back to the state holder that asked, which knows what it was for. */
    data class Runtime(val permission: String) : WatchPermissionRequest
}

/** One device's screen, the part every device has; its family's panel has a state holder of its own. */
@HiltViewModel
class WatchDeviceViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val deviceRepository: BleDeviceRepository,
    private val syncController: DeviceSyncController,
    private val autoSyncScheduler: WatchAutoSyncScheduler,
    private val sensorCoordinator: BleSensorCoordinator,
    private val healthConnectManager: HealthConnectGateway,
    private val families: DeviceFamilies,
    dispatchers: DispatcherProvider,
    @ApplicationScope appScope: CoroutineScope,
) : ViewModel() {

    val deviceId: String = savedStateHandle.toRoute<DeviceDetail>().deviceId

    /** Outlives the view-model: the unbond after a removal must not die with the screen. */
    private val housekeepingScope = appScope + dispatchers.default

    private val localState = MutableStateFlow(WatchDeviceUiState())

    val uiState: StateFlow<WatchDeviceUiState> = combine(
        deviceRepository.devicesFlow,
        syncController.state,
        autoSyncScheduler.intervalFlow(deviceId),
        localState,
    ) { devices, sync, autoSync, local ->
        val device = devices.firstOrNull { it.id == deviceId }
        local.copy(
            device = device,
            family = device?.let(families::of)?.id?.let(DeviceIntegration::fromStorage),
            features = device?.let(families::features).orEmpty(),
            offersHistoryDeletion = device?.let(families::offersHistoryDeletion) == true,
            sync = sync,
            autoSync = autoSync,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WatchDeviceUiState())

    /**
     * Picks the auto-sync interval. The scheduler owns the stored choice and the periodic work.
     * Switching it on is the moment to ask for the background read: from now on syncs run with
     * the app closed.
     */
    fun selectAutoSync(interval: AutoSyncInterval) {
        val switchingOn = autoSyncScheduler.interval(deviceId) == AutoSyncInterval.OFF && interval != AutoSyncInterval.OFF
        autoSyncScheduler.setInterval(deviceId, interval)
        if (switchingOn && localState.value.backgroundReadMissing) allowBackgroundRead()
    }

    /** Asks Health Connect for the background read. */
    fun allowBackgroundRead() {
        val permissions = setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
        localState.update { it.copy(permissionRequest = WatchPermissionRequest.HealthConnect(permissions)) }
    }

    /** The screen launched [WatchDeviceUiState.permissionRequest]. */
    fun onPermissionRequestHandled() {
        localState.update { it.copy(permissionRequest = null) }
    }

    /** Re-reads the background grant: on resume, and after the permission screen returns. */
    fun refreshBackgroundAccess() {
        viewModelScope.launch {
            val missing = runSuspendCatching { healthConnectManager.isBackgroundReadGrantMissing() }.getOrDefault(false)
            localState.update { it.copy(backgroundReadMissing = missing) }
        }
    }

    fun syncNow() {
        syncController.syncDevice(deviceId)
    }

    /** Renames the watch without touching anything else it holds. */
    fun rename(displayName: String) {
        val trimmed = displayName.trim()
        if (trimmed.isEmpty()) return
        deviceRepository.updateDevice(deviceId = deviceId, displayName = trimmed)
    }

    fun setEnabled(enabled: Boolean) {
        deviceRepository.setDeviceEnabled(deviceId, enabled)
    }

    /**
     * Probes a bike computer for the GATT services it is broadcasting now and
     * persists them as capabilities. From the device card: broadcast mode is
     * usually only on during a ride.
     */
    fun detectBroadcastSensors() {
        val device = deviceRepository.devices.firstOrNull { it.id == deviceId } ?: return
        if (!device.isLiveSensorCapable || localState.value.isDetectingSensors) return
        localState.update { it.copy(isDetectingSensors = true, detectFoundNothing = false) }
        viewModelScope.launch {
            val found = sensorCoordinator.discoverCapabilities(device.address)
            // The assigned capabilities are the fallback when the connect finds nothing.
            val capabilities = found.ifEmpty { device.capabilities }
            if (capabilities != device.capabilities) {
                deviceRepository.updateDevice(deviceId = deviceId, capabilities = capabilities)
            }
            localState.update {
                it.copy(isDetectingSensors = false, detectFoundNothing = found.isEmpty())
            }
        }
    }

    /**
     * Removes the device: its registry row and schedule now, then what its family holds for it
     * (state, keys, the system's bond or association, and with [deleteWatchHistory] the history
     * [WatchDeviceUiState.offersHistoryDeletion] named), fire and forget.
     */
    fun removeDevice(deleteWatchHistory: Boolean = false) {
        // Read the device before forgetting it: its family needs the address.
        val device = deviceRepository.devices.firstOrNull { it.id == deviceId }
        deviceRepository.removeDevice(deviceId)
        // Before the early return: a schedule left running would wake the radio for nothing.
        autoSyncScheduler.forget(deviceId)
        if (device == null) return
        housekeepingScope.launch { families.forget(device, deleteWatchHistory) }
    }
}
