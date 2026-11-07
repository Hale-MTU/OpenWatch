package tech.mmarca.openwatch.features.watches

import android.Manifest
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.mmarca.openwatch.core.permissions.OsPermissionsService
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.devices.core.DeviceClassification
import tech.mmarca.openwatch.devices.wearos.OnboardWearOsWatchUseCase
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleDiscoveredDevice
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration
import tech.mmarca.openwatch.domain.model.OsPermissionCatalog
import tech.mmarca.openwatch.domain.model.OsPermissionId
import tech.mmarca.openwatch.sensors.ble.BleSensorCoordinator

/** What the last onboarding wants the list screen to say. */
enum class WatchOnboardNotice { NO_COMPANION }

@Immutable
data class WatchesUiState(
    /** The paired watches, never sensors. */
    val watches: List<BleSensorDevice> = emptyList(),
    /** Scanned devices that classify as a watch. */
    val discoveredWatches: List<BleDiscoveredDevice> = emptyList(),
    val isScanning: Boolean = false,
    val showAddFlow: Boolean = false,
    val selectedDevice: BleDiscoveredDevice? = null,
    val selectedClassification: DeviceClassification? = null,
    val addDisplayName: String = "",
    val isOnboarding: Boolean = false,
    val onboardNotice: WatchOnboardNotice? = null,
    val errorMessage: String? = null,
    /** Android's own permissions for watch pairing and background sync. */
    val osPermissions: OsPermissionCatalog = OsPermissionCatalog(),
    /** Shown instead of the scan while permissions are outstanding. */
    val showPermissionsGate: Boolean = false,
    /** Settings-screen walks still queued, one per resume. Survives leaving the app. */
    val pendingSpecialPermissions: List<OsPermissionId> = emptyList(),
) {
    val addingIntegration: DeviceIntegration?
        get() = selectedClassification?.integration
}

/**
 * The Watches screen's state: the paired list plus the scan, classify,
 * onboard flow. Its own view-model: sensors and watches share the radio and
 * the registry, nothing else.
 */
@HiltViewModel
class WatchesViewModel @Inject constructor(
    private val deviceRepository: BleDeviceRepository,
    private val sensorCoordinator: BleSensorCoordinator,
    private val onboardWearOsWatch: OnboardWearOsWatchUseCase,
    private val osPermissionsService: OsPermissionsService,
) : ViewModel() {

    private val localState = MutableStateFlow(
        WatchesUiState(osPermissions = osPermissionsService.watchSetupCatalog()),
    )

    val uiState: StateFlow<WatchesUiState> = combine(
        deviceRepository.devicesFlow,
        sensorCoordinator.discoveredDevices,
        localState,
    ) { devices, discovered, local ->
        local.copy(
            watches = devices.filter { it.isWatch },
            discoveredWatches = discovered.filter { device ->
                sensorCoordinator.classifyDiscoveredDevice(device).kind != BleDeviceKind.SENSOR
            },
        )
    }.stateInViewModel(initial = WatchesUiState())

    val requiredBluetoothPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            // Below API 31 a BLE scan finds nothing without fine location.
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun refresh() {
        deviceRepository.refresh()
        refreshOsPermissions()
    }

    fun refreshOsPermissions() {
        localState.update { it.copy(osPermissions = osPermissionsService.watchSetupCatalog()) }
    }

    /** The add button's entry point. Outstanding permissions get the checklist first. */
    fun startAdd() {
        val catalog = osPermissionsService.watchSetupCatalog()
        localState.update { it.copy(osPermissions = catalog) }
        if (catalog.allGranted) {
            openAddFlow()
        } else {
            localState.update { it.copy(showPermissionsGate = true, errorMessage = null) }
        }
    }

    fun dismissPermissionsGate() {
        localState.update {
            it.copy(showPermissionsGate = false, pendingSpecialPermissions = emptyList())
        }
    }

    /** Queues every outstanding settings walk, for the gate's "Grant all". */
    fun queueAllSpecialPermissions() {
        localState.update { it.copy(pendingSpecialPermissions = it.osPermissions.outstandingSpecials) }
    }

    fun queueSpecialPermission(id: OsPermissionId) {
        localState.update { it.copy(pendingSpecialPermissions = listOf(id)) }
    }

    /**
     * Opens the next queued settings screen, one per call, on every resume.
     * Re-reads the catalog first, so a finished walk is dropped.
     */
    fun openNextSpecialPermission() {
        val queued = localState.value.pendingSpecialPermissions
        if (queued.isEmpty()) return
        val fresh = osPermissionsService.watchSetupCatalog()
        val remaining = queued.filter { id -> fresh.rows.any { it.id == id && !it.granted } }
        localState.update {
            it.copy(osPermissions = fresh, pendingSpecialPermissions = remaining.drop(1))
        }
        val next = remaining.firstOrNull() ?: return
        osPermissionsService.openSettingsFor(next)
    }

    fun openAddFlow() {
        localState.update {
            it.copy(
                showPermissionsGate = false,
                showAddFlow = true,
                selectedDevice = null,
                selectedClassification = null,
                addDisplayName = "",
                errorMessage = null,
                // Describes the last onboarding; cleared when a new flow starts.
                onboardNotice = null,
            )
        }
        startScan()
    }

    fun closeAddFlow() {
        stopScan()
        localState.update {
            it.copy(
                showAddFlow = false,
                selectedDevice = null,
                selectedClassification = null,
                errorMessage = null,
            )
        }
    }

    fun dismissNotice() {
        localState.update { it.copy(onboardNotice = null) }
    }

    fun startScan() {
        localState.update { it.copy(isScanning = true, errorMessage = null) }
        sensorCoordinator.startScan(showAllDevices = false)
    }

    fun stopScan() {
        sensorCoordinator.stopScan()
        localState.update { it.copy(isScanning = false) }
    }

    /** Choosing stops the scan. */
    fun selectDiscoveredDevice(device: BleDiscoveredDevice) {
        val classification = sensorCoordinator.classifyDiscoveredDevice(device)
        localState.update {
            it.copy(
                selectedDevice = device,
                selectedClassification = classification,
                addDisplayName = device.name ?: device.address,
                errorMessage = null,
            )
        }
        stopScan()
    }

    fun updateAddDisplayName(value: String) {
        localState.update { it.copy(addDisplayName = value) }
    }

    /** Bonds and registers the selected watch. [onDone] gets `true` when the sheet should close. */
    fun onboardSelectedWatch(onDone: (Boolean) -> Unit = {}) {
        val state = localState.value
        val selected = state.selectedDevice
        if (selected == null || state.isOnboarding) {
            onDone(false)
            return
        }
        val displayName = state.addDisplayName.trim()
            .ifBlank { selected.name ?: selected.address }

        // No bond, just the optional association.
        if (state.addingIntegration != DeviceIntegration.WEAROS) {
            onDone(false)
            return
        }
        localState.update {
            it.copy(isOnboarding = true, errorMessage = null, onboardNotice = null)
        }
        viewModelScope.launch {
            val outcome = onboardWearOsWatch(selected, displayName = displayName)
            localState.update {
                it.copy(
                    isOnboarding = false,
                    onboardNotice = if (outcome.associated) null else WatchOnboardNotice.NO_COMPANION,
                )
            }
            closeAddFlow()
            onDone(true)
        }
    }

    override fun onCleared() {
        stopScan()
        super.onCleared()
    }

    private fun <T> kotlinx.coroutines.flow.Flow<T>.stateInViewModel(initial: T): StateFlow<T> {
        val state = MutableStateFlow(initial)
        viewModelScope.launch {
            collect { value -> state.value = value }
        }
        return state.asStateFlow()
    }
}
