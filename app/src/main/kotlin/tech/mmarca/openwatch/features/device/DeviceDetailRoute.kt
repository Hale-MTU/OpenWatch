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
import tech.mmarca.openwatch.devices.core.DeviceFamilies
import tech.mmarca.openwatch.features.watches.WatchDeviceRoute
import tech.mmarca.openwatch.navigation.DeviceDetail

/** The screen that draws a paired device. */
sealed interface DeviceDetailScreen {
    /** The part every device has, and its family's panel. */
    data object Device : DeviceDetailScreen

    /** The family's own screen, its [DeviceScreenContribution] (the scale's: its weigh-ins, its key, its listening). */
    data class Family(val contribution: DeviceScreenContribution) : DeviceDetailScreen
}

/**
 * Picks [DeviceDetail]'s screen from the device's registry row: its family's own screen when the
 * family brings one, the device screen otherwise. Decided once, from the registry as first read:
 * removing a device empties its row while its screen is still on its way back, and the screen
 * must not change under it.
 */
@HiltViewModel
class DeviceDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    deviceRepository: BleDeviceRepository,
    families: DeviceFamilies,
    screens: Map<String, @JvmSuppressWildcards DeviceScreenContribution>,
) : ViewModel() {

    private val deviceId: String = savedStateHandle.toRoute<DeviceDetail>().deviceId

    /** Null until the registry is first read. A device gone by then gets the device screen, which says it is gone. */
    val screen: StateFlow<DeviceDetailScreen?> = flow {
        val device = deviceRepository.devicesFlow.first().firstOrNull { it.id == deviceId }
        val own = device?.let(families::of)?.id?.let(screens::get)
        emit(own?.let(DeviceDetailScreen::Family) ?: DeviceDetailScreen.Device)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

/** The [DeviceDetail] destination: the screen its device's family draws, with that screen's ways out. */
@Composable
fun DeviceDetailRoute(
    host: DeviceScreenHost,
    viewModel: DeviceDetailViewModel = hiltViewModel(),
) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    when (val current = screen) {
        null -> Unit
        is DeviceDetailScreen.Family -> current.contribution.Screen(host)
        DeviceDetailScreen.Device -> WatchDeviceRoute(host = host)
    }
}
