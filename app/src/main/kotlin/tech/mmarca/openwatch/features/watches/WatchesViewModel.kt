package tech.mmarca.openwatch.features.watches

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
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
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleDiscoveredDevice
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration
import tech.mmarca.openwatch.domain.model.OsPermissionCatalog
import tech.mmarca.openwatch.domain.model.OsPermissionId
import tech.mmarca.openwatch.features.device.WatchOnboardResult
import tech.mmarca.openwatch.features.device.WatchOnboarding
import tech.mmarca.openwatch.sensors.ble.BleSensorCoordinator
import tech.mmarca.openwatch.core.presentation.ScreenError

/** What the last onboarding wants the list screen to say. An unsupported transport outranks a missing association. */
enum class WatchOnboardNotice { UNSUPPORTED_TRANSPORT, NO_COMPANION }

@Immutable
data class WatchesUiState(
    /** The paired watches — never sensors, never bike computers' sensor role. */
    val watches: List<BleSensorDevice> = emptyList(),
    /** Scanned devices that classify as a watch or bike computer. */
    val discoveredWatches: List<BleDiscoveredDevice> = emptyList(),
    val isScanning: Boolean = false,
    val showAddFlow: Boolean = false,
    val selectedDevice: BleDiscoveredDevice? = null,
    val selectedClassification: DeviceClassification? = null,
    val addDisplayName: String = "",
    val isOnboarding: Boolean = false,
    /** The step of the family's pairing that runs, by its index in the family's checklist; null outside pairing. */
    val onboardStep: Int? = null,
    val onboardNotice: WatchOnboardNotice? = null,
    /** Why the last pairing did not finish; translated when shown. */
    val error: ScreenError? = null,
    /** The last WearOS onboarding ended without the companion association; nothing was added. */
    val companionRequired: Boolean = false,
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
    private val savedStateHandle: SavedStateHandle,
    private val deviceRepository: BleDeviceRepository,
    private val sensorCoordinator: BleSensorCoordinator,
    /** Each watch family's pairing, by its persisted name. */
    private val onboardings: Map<String, @JvmSuppressWildcards WatchOnboarding>,
    private val osPermissionsService: OsPermissionsService,
) : ViewModel() {

    /**
     * The permission walk sends the user to Android's settings screens, where the process may be
     * reclaimed: the gate, the queue and the open add dialog come back from [savedStateHandle].
     * The scan's results do not survive anyway, so the selection is not kept.
     */
    private val localState = MutableStateFlow(
        WatchesUiState(
            osPermissions = osPermissionsService.watchSetupCatalog(),
            showPermissionsGate = savedStateHandle[KEY_PERMISSIONS_GATE] ?: false,
            pendingSpecialPermissions = savedStateHandle.get<ArrayList<String>>(KEY_PENDING_SPECIALS).orEmpty()
                .mapNotNull { name -> OsPermissionId.entries.firstOrNull { it.name == name } },
            showAddFlow = savedStateHandle[KEY_ADD_FLOW] ?: false,
            addDisplayName = savedStateHandle[KEY_ADD_NAME] ?: "",
        ),
    )

    init {
        viewModelScope.launch {
            localState.collect { state ->
                savedStateHandle[KEY_PERMISSIONS_GATE] = state.showPermissionsGate
                savedStateHandle[KEY_PENDING_SPECIALS] = ArrayList(state.pendingSpecialPermissions.map { it.name })
                savedStateHandle[KEY_ADD_FLOW] = state.showAddFlow
                savedStateHandle[KEY_ADD_NAME] = state.addDisplayName
            }
        }
    }

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


    fun refresh() {
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
            localState.update { it.copy(showPermissionsGate = true, error = null) }
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
                error = null,
                companionRequired = false,
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
                error = null,
                companionRequired = false,
            )
        }
    }

    fun dismissNotice() {
        localState.update { it.copy(onboardNotice = null) }
    }

    fun startScan() {
        localState.update { it.copy(isScanning = true, error = null) }
        sensorCoordinator.startScan(showAllDevices = false)
    }

    fun stopScan() {
        sensorCoordinator.stopScan()
        localState.update { it.copy(isScanning = false) }
    }

    /** A GFDI device is bonded, not given capabilities. Choosing stops the scan. */
    fun selectDiscoveredDevice(device: BleDiscoveredDevice) {
        val classification = sensorCoordinator.classifyDiscoveredDevice(device)
        localState.update {
            it.copy(
                selectedDevice = device,
                selectedClassification = classification,
                addDisplayName = device.name ?: device.address,
                error = null,
            )
        }
        stopScan()
    }

    fun updateAddDisplayName(value: String) {
        localState.update { it.copy(addDisplayName = value) }
    }

    /** The pairing of [integration]'s family, which the add sheet runs and whose steps it shows. */
    fun onboardingFor(integration: DeviceIntegration?): WatchOnboarding? =
        integration?.let { onboardings[it.storageName] }

    /**
     * Pairs and registers the selected watch through its family's onboarding. Success closes the
     * sheet through the state; a failure, or a family that needs the companion association and
     * was not given it, leaves it open.
     */
    fun onboardSelectedWatch() {
        val state = localState.value
        val selected = state.selectedDevice
        if (selected == null || state.isOnboarding) return
        val onboarding = onboardingFor(state.addingIntegration) ?: return
        val displayName = state.addDisplayName.trim()
            .ifBlank { selected.name ?: selected.address }

        localState.update {
            it.copy(isOnboarding = true, onboardStep = null, error = null, companionRequired = false, onboardNotice = null)
        }
        viewModelScope.launch {
            val result = onboarding.onboard(
                selected,
                displayName = displayName,
                // Registered as the classified kind: watch, or BIKE_COMPUTER for an Edge.
                kind = state.selectedClassification?.kind ?: BleDeviceKind.WATCH,
                onStep = { step ->
                    localState.update { current ->
                        if (current.isOnboarding) current.copy(onboardStep = step) else current
                    }
                },
            )
            when (result) {
                is WatchOnboardResult.Paired -> {
                    localState.update {
                        it.copy(isOnboarding = false, onboardStep = null, onboardNotice = result.notice)
                    }
                    closeAddFlow()
                }

                is WatchOnboardResult.Failed -> localState.update {
                    it.copy(isOnboarding = false, onboardStep = null, error = result.reason)
                }

                // The sheet stays open, so the user can try again and allow the request.
                WatchOnboardResult.CompanionRequired -> localState.update {
                    it.copy(isOnboarding = false, onboardStep = null, companionRequired = true)
                }
            }
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

private const val KEY_PERMISSIONS_GATE = "watches_permissions_gate"
private const val KEY_PENDING_SPECIALS = "watches_pending_specials"
private const val KEY_ADD_FLOW = "watches_add_flow"
private const val KEY_ADD_NAME = "watches_add_name"
