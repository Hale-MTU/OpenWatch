package tech.mmarca.openwatch.features.device

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.devices.xiaomi.isXiaomiScale
import tech.mmarca.openwatch.features.scales.ScaleDeviceRoute
import tech.mmarca.openwatch.features.watches.WatchDeviceRoute
import tech.mmarca.openwatch.navigation.DeviceDetail

/** The screen that draws a paired device. */
enum class DeviceDetailScreen {
    /** The part every device has, and its family's panel. */
    DEVICE,

    /** The scale's own screen: its weigh-ins, its key and its listening. */
    SCALE,
}

/**
 * Picks [DeviceDetail]'s screen from the device's registry row. Decided once, from the registry
 * as first read: removing a device empties its row while its screen is still on its way back,
 * and the screen must not change under it.
 */
@HiltViewModel
class DeviceDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    deviceRepository: BleDeviceRepository,
) : ViewModel() {

    private val deviceId: String = savedStateHandle.toRoute<DeviceDetail>().deviceId

    /** Null until the registry is first read. A device gone by then gets the device screen, which says it is gone. */
    val screen: StateFlow<DeviceDetailScreen?> = flow {
        val device = deviceRepository.devicesFlow.first().firstOrNull { it.id == deviceId }
        emit(if (device?.isXiaomiScale == true) DeviceDetailScreen.SCALE else DeviceDetailScreen.DEVICE)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

/** The [DeviceDetail] destination: the screen its device's family draws, with that screen's ways out. */
@Composable
fun DeviceDetailRoute(
    onOpenData: (String) -> Unit,
    onOpenNotifications: (String) -> Unit,
    onOpenWatchSettings: (String, Int) -> Unit,
    onOpenSendPoint: (String) -> Unit,
    onOpenAlarms: (String) -> Unit,
    onOpenBodyProfile: () -> Unit,
    onRemoved: () -> Unit,
    viewModel: DeviceDetailViewModel = hiltViewModel(),
) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    when (screen) {
        null -> Unit
        DeviceDetailScreen.SCALE -> ScaleDeviceRoute(onOpenBodyProfile = onOpenBodyProfile, onRemoved = onRemoved)
        DeviceDetailScreen.DEVICE -> WatchDeviceRoute(
            onOpenData = onOpenData,
            onOpenNotifications = onOpenNotifications,
            onOpenWatchSettings = onOpenWatchSettings,
            onOpenSendPoint = onOpenSendPoint,
            onOpenAlarms = onOpenAlarms,
            onRemoved = onRemoved,
        )
    }
}
