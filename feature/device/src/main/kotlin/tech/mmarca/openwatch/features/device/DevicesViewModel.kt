package tech.mmarca.openwatch.features.device

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.mmarca.openwatch.core.permissions.OsPermissionsService
import tech.mmarca.openwatch.core.presentation.ScreenError
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.devices.core.DeviceClassification
import tech.mmarca.openwatch.devices.core.DeviceFamilies
import tech.mmarca.openwatch.devices.core.DevicePairingResult
import tech.mmarca.openwatch.devices.core.PairingFailure
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleDiscoveredDevice
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.OsPermissionCatalog
import tech.mmarca.openwatch.sensors.ble.BleSensorCoordinator

/** A scanned device the add flow offers: the family that claims it, and the kind it would be paired as. */
@Immutable
data class DeviceCandidate(val device: BleDiscoveredDevice, val familyId: String, val kind: BleDeviceKind)

@Immutable
data class DevicesUiState(
    /** Whether any family pairs through this flow; the section shows only then. */
    val available: Boolean = false,
    /** The paired devices of those families. */
    val devices: List<BleSensorDevice> = emptyList(),
    /** What the scan found that one of those families claims and no watch family does. */
    val candidates: List<DeviceCandidate> = emptyList(),
    val isScanning: Boolean = false,
    val showAddFlow: Boolean = false,
    val selected: DeviceCandidate? = null,
    val nameInput: String = "",
    val isPairing: Boolean = false,
    /** Why the last pairing did not finish; translated when shown. */
    val error: ScreenError? = null,
    val osPermissions: OsPermissionCatalog = OsPermissionCatalog(),
    /** Shown instead of the scan while the Bluetooth grants are outstanding. */
    val showPermissionsGate: Boolean = false,
)

private data class DevicesLocalState(
    val isScanning: Boolean = false,
    val showAddFlow: Boolean = false,
    val selected: DeviceCandidate? = null,
    val nameInput: String = "",
    val isPairing: Boolean = false,
    val error: ScreenError? = null,
    val osPermissions: OsPermissionCatalog = OsPermissionCatalog(),
    val showPermissionsGate: Boolean = false,
)

/**
 * The Home section of the devices a family pairs through the app's own flow
 * ([tech.mmarca.openwatch.devices.core.DevicePairing]): its paired list and the add flow. It names
 * no family: what the scan listens for, which devices are offered and how one is paired are the
 * families' to say. The watches and the S400 scale keep their own sections.
 */
@HiltViewModel
class DevicesViewModel @Inject constructor(
    deviceRepository: BleDeviceRepository,
    private val families: DeviceFamilies,
    private val sensorCoordinator: BleSensorCoordinator,
    private val osPermissionsService: OsPermissionsService,
) : ViewModel() {

    private val local = MutableStateFlow(DevicesLocalState(osPermissions = osPermissionsService.deviceSetupCatalog()))

    val uiState: StateFlow<DevicesUiState> = combine(
        deviceRepository.devicesFlow,
        sensorCoordinator.discoveredDevices,
        local,
    ) { devices, discovered, state ->
        DevicesUiState(
            available = families.pairing.isNotEmpty(),
            devices = devices.filter { families.of(it)?.pairing != null },
            candidates = if (state.showAddFlow) candidates(discovered) else emptyList(),
            isScanning = state.isScanning,
            showAddFlow = state.showAddFlow,
            selected = state.selected,
            nameInput = state.nameInput,
            isPairing = state.isPairing,
            error = state.error,
            osPermissions = state.osPermissions,
            showPermissionsGate = state.showPermissionsGate,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DevicesUiState(available = families.pairing.isNotEmpty()))

    fun refreshOsPermissions() {
        local.update { it.copy(osPermissions = osPermissionsService.deviceSetupCatalog()) }
    }

    /** The add button: the Bluetooth grants first, when they are outstanding. */
    fun startAdd() {
        val catalog = osPermissionsService.deviceSetupCatalog()
        local.update { it.copy(osPermissions = catalog, error = null) }
        if (catalog.allGranted) openAddFlow() else local.update { it.copy(showPermissionsGate = true) }
    }

    fun dismissPermissionsGate() {
        local.update { it.copy(showPermissionsGate = false) }
    }

    fun openAddFlow() {
        local.update {
            it.copy(showPermissionsGate = false, showAddFlow = true, selected = null, nameInput = "", error = null)
        }
        startScan()
    }

    fun closeAddFlow() {
        stopScan()
        local.update { it.copy(showAddFlow = false, selected = null, error = null) }
    }

    fun startScan() {
        local.update { it.copy(isScanning = true, error = null) }
        val services = families.pairing.values.flatMap { it.scanServiceUuids }.distinct().map(UUID::fromString)
        sensorCoordinator.startScan(serviceUuids = services)
    }

    fun stopScan() {
        sensorCoordinator.stopScan()
        local.update { it.copy(isScanning = false) }
    }

    /** Choosing a device stops the scan and names it after itself. */
    fun select(candidate: DeviceCandidate) {
        stopScan()
        local.update {
            it.copy(selected = candidate, nameInput = candidate.device.name ?: candidate.device.address, error = null)
        }
    }

    fun updateName(value: String) {
        local.update { it.copy(nameInput = value) }
    }

    /** Pairs the chosen device through its family. Success closes the dialog; a failure leaves it open. */
    fun pair() {
        val state = local.value
        val candidate = state.selected ?: return
        if (state.isPairing) return
        val pairing = families.pairing[candidate.familyId] ?: return
        val name = state.nameInput.trim().ifBlank { candidate.device.name ?: candidate.device.address }
        local.update { it.copy(isPairing = true, error = null) }
        viewModelScope.launch {
            when (val result = pairing.pair(candidate.device, name)) {
                is DevicePairingResult.Paired -> {
                    local.update { it.copy(isPairing = false) }
                    closeAddFlow()
                }
                is DevicePairingResult.Failed -> local.update {
                    it.copy(isPairing = false, error = ScreenError.Text(result.reason.message()))
                }
            }
        }
    }

    override fun onCleared() {
        sensorCoordinator.stopScan()
    }

    /** Each scanned device a pairing family claims, under the first that does; never one a watch family claims. */
    private fun candidates(discovered: List<BleDiscoveredDevice>): List<DeviceCandidate> =
        discovered
            .filter { sensorCoordinator.classifyDiscoveredDevice(it) == DeviceClassification.SENSOR }
            .mapNotNull { device ->
                families.pairing.entries.firstNotNullOfOrNull { (familyId, pairing) ->
                    pairing.claim(device)?.let { kind -> DeviceCandidate(device, familyId, kind) }
                }
            }
}

private fun PairingFailure.message(): Int = when (this) {
    PairingFailure.BOND_REFUSED -> R.string.devices_pair_refused
    PairingFailure.UNREACHABLE -> R.string.devices_pair_unreachable
    PairingFailure.NOT_ASSOCIATED -> R.string.devices_pair_not_associated
}
