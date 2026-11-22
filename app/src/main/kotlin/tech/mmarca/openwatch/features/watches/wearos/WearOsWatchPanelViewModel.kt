package tech.mmarca.openwatch.features.watches.wearos

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.mmarca.openwatch.core.permissions.RuntimePermissions
import tech.mmarca.openwatch.core.presentation.ScreenError
import tech.mmarca.openwatch.core.presentation.toScreenError
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.devices.wearos.OnboardWearOsWatchUseCase
import tech.mmarca.openwatch.devices.wearos.WearOsCompanionManager
import tech.mmarca.openwatch.devices.wearos.WearOsCompanionStatus
import tech.mmarca.openwatch.devices.wearos.WearOsLinkSnapshot
import tech.mmarca.openwatch.devices.wearos.WearOsLinkStore
import tech.mmarca.openwatch.devices.wearos.isWearOs
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.features.watches.WatchPermissionRequest
import tech.mmarca.openwatch.navigation.WATCH_DEVICE_ID_ARG

/** What the Wear OS panel of the device view shows: the link's status and the watch's own settings. */
@Immutable
data class WearOsWatchPanelUiState(
    /** Wear OS connection and app responsiveness state. */
    val wearOsStatus: WearOsCompanionStatus = WearOsCompanionStatus(),
    /** True while a Wear OS status check / ping is in flight. */
    val isCheckingWearOsStatus: Boolean = false,
    /** Why the last Wear OS check failed. [ScreenError.PermissionDenied] gets a grant affordance. */
    val wearOsError: ScreenError? = null,
    /**
     * The Wear OS watch has no companion association: it was added before the association
     * was required, or the user removed it in Android's settings.
     */
    val wearOsCompanionMissing: Boolean = false,
    /** True while Android's association dialog is open. */
    val isAssociatingWearOs: Boolean = false,
    /** The watch's own settings as it last reported them; null until read or when it cannot serve them. */
    val watchSettings: Map<String, String>? = null,
    /** True while the watch's settings are read or one is being changed. */
    val isWatchSettingsBusy: Boolean = false,
    /** Why the last read or change of the watch's settings failed. */
    val watchSettingsError: ScreenError? = null,
    /** A grant for the screen to ask for, once; held until [WearOsWatchPanelViewModel.onPermissionRequestHandled]. */
    val permissionRequest: WatchPermissionRequest.Runtime? = null,
)

/** One Wear OS watch's panel: the link check, the association, the watch's settings and its live heart rate. */
@HiltViewModel
class WearOsWatchPanelViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val deviceRepository: BleDeviceRepository,
    private val companionManager: WearOsCompanionManager,
    private val linkStore: WearOsLinkStore,
    private val onboardWearOsWatch: OnboardWearOsWatchUseCase,
) : ViewModel() {

    val deviceId: String = savedStateHandle.get<String>(WATCH_DEVICE_ID_ARG).orEmpty()

    private val localState = MutableStateFlow(WearOsWatchPanelUiState())

    val uiState: StateFlow<WearOsWatchPanelUiState> = localState.asStateFlow()

    /**
     * The watch's heart rate as it happens, null while none arrives.
     * Streams only while the screen collects it, so the watch's sensor runs
     * unbatched only while someone looks.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val liveHeartRateBpm: StateFlow<Int?> = deviceRepository.devicesFlow
        .map { devices -> devices.firstOrNull { it.id == deviceId }?.takeIf { it.isWearOs } }
        .distinctUntilChanged { old, new -> old?.address == new?.address && old?.bluetoothName == new?.bluetoothName }
        .flatMapLatest { device ->
            device?.let { companionManager.liveHeartRate(it.address, it.bluetoothName) } ?: flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(LIVE_STOP_TIMEOUT_MS), null)

    init {
        // The first check runs once the device has loaded; later ones are by hand. The watch's
        // settings are read after it: the watch serves one connection at a time.
        viewModelScope.launch {
            val device = deviceRepository.devicesFlow.mapNotNull { devices -> devices.firstOrNull { it.id == deviceId } }.first()
            if (device.isWearOs) {
                checkWearOsStatus()
                uiState.first { !it.isCheckingWearOsStatus }
                loadWatchSettings()
            }
        }
        // A sync that ends in a typed failure updates the status row without another ping.
        viewModelScope.launch {
            linkStore.snapshots.collect { snapshots ->
                val snapshot = snapshots[deviceId] ?: return@collect
                localState.update { state ->
                    state.copy(
                        wearOsStatus = state.wearOsStatus.copy(
                            appStatus = snapshot.status,
                            watchName = snapshot.watchName ?: state.wearOsStatus.watchName,
                            bondLost = snapshot.bondLost,
                            lastCheckedAt = snapshot.checkedAt,
                        ),
                    )
                }
            }
        }
    }

    /** Checks whether the Wear OS watch is paired and its OpenWatch app answers. */
    fun checkWearOsStatus() {
        val device = currentDevice() ?: return
        if (!device.isWearOs || localState.value.isCheckingWearOsStatus) return
        localState.update {
            it.copy(
                isCheckingWearOsStatus = true,
                wearOsCompanionMissing = !onboardWearOsWatch.isAssociated(device.address),
            )
        }
        viewModelScope.launch {
            try {
                val status = companionManager.checkWearOsWatchStatus(
                    targetAddress = device.address,
                    targetName = device.bluetoothName,
                )
                localState.update { it.copy(wearOsStatus = status, wearOsError = null) }
                linkStore.record(
                    device.id,
                    WearOsLinkSnapshot(status.appStatus, watchName = status.watchName, bondLost = status.bondLost, checkedAt = Instant.now()),
                )
            } catch (e: Exception) {
                val error = e.toScreenError(logTag = TAG, logMessage = "Wear OS status check failed")
                localState.update { it.copy(wearOsError = error) }
            } finally {
                localState.update { it.copy(isCheckingWearOsStatus = false) }
            }
        }
    }

    /** Asks Android for the companion association of a Wear OS watch that has none. */
    fun associateWearOs() {
        val device = currentDevice() ?: return
        if (!device.isWearOs || localState.value.isAssociatingWearOs) return
        localState.update { it.copy(isAssociatingWearOs = true) }
        viewModelScope.launch {
            val associated = onboardWearOsWatch.associate(device.address, device.displayName)
            localState.update { it.copy(isAssociatingWearOs = false, wearOsCompanionMissing = !associated) }
        }
    }

    /**
     * The status card's "nearby devices" grant: without it the phone cannot see the bond or open
     * the link. Before Android 12 there is no such grant, so the check runs again at once.
     */
    fun requestBluetoothConnect() {
        val permission = RuntimePermissions.bluetoothConnectPermission()
        if (permission == null) {
            checkWearOsStatus()
        } else {
            // Kept apart from the request, which the screen clears on launch, for the answer to find.
            savedStateHandle[PENDING_BLUETOOTH_REQUEST_KEY] = true
            localState.update { it.copy(permissionRequest = WatchPermissionRequest.Runtime(permission)) }
        }
    }

    /** The screen launched [WearOsWatchPanelUiState.permissionRequest]; its answer comes to [onRuntimePermissionResult]. */
    fun onPermissionRequestHandled() {
        localState.update { it.copy(permissionRequest = null) }
    }

    /** The runtime dialog answered: with the grant, the check runs again. */
    fun onRuntimePermissionResult(granted: Boolean) {
        if (savedStateHandle.remove<Boolean>(PENDING_BLUETOOTH_REQUEST_KEY) != true) return
        if (granted) checkWearOsStatus()
    }

    /** Reads the watch's own settings over the link. */
    fun loadWatchSettings() {
        val device = currentDevice() ?: return
        if (!device.isWearOs || localState.value.isWatchSettingsBusy) return
        localState.update { it.copy(isWatchSettingsBusy = true) }
        viewModelScope.launch {
            try {
                val held = companionManager.watchSettings(device.address, device.bluetoothName)
                localState.update { it.copy(watchSettings = held, watchSettingsError = null) }
            } catch (e: Exception) {
                localState.update { it.copy(watchSettingsError = e.toScreenError(logTag = TAG, logMessage = "Watch settings read failed")) }
            } finally {
                localState.update { it.copy(isWatchSettingsBusy = false) }
            }
        }
    }

    /** Changes one of the watch's settings, then reads them all back: the watch is the truth. */
    fun setWatchSetting(key: String, value: String) {
        val device = currentDevice() ?: return
        if (localState.value.isWatchSettingsBusy) return
        localState.update { state -> state.copy(isWatchSettingsBusy = true, watchSettings = state.watchSettings?.plus(key to value)) }
        viewModelScope.launch {
            try {
                companionManager.setWatchSetting(device.address, device.bluetoothName, key, value)
                val held = companionManager.watchSettings(device.address, device.bluetoothName)
                localState.update { it.copy(watchSettings = held, watchSettingsError = null) }
            } catch (e: Exception) {
                localState.update { it.copy(watchSettingsError = e.toScreenError(logTag = TAG, logMessage = "Watch setting change failed")) }
            } finally {
                localState.update { it.copy(isWatchSettingsBusy = false) }
            }
        }
    }

    private fun currentDevice(): BleSensorDevice? = deviceRepository.devices.firstOrNull { it.id == deviceId }
}

private const val TAG = "WearOsWatchPanel"

/** A Bluetooth grant request is outstanding, kept through a process death so its answer still lands. */
private const val PENDING_BLUETOOTH_REQUEST_KEY = "pendingBluetoothRequest"

/** Keeps the live stream through a rotation; longer, and the watch's sensor runs for nothing. */
private const val LIVE_STOP_TIMEOUT_MS = 5_000L
