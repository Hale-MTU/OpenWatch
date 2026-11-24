package tech.mmarca.openwatch.features.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tech.mmarca.openwatch.features.device.DevicesSectionRoute
import tech.mmarca.openwatch.features.scales.ScalesSectionRoute
import tech.mmarca.openwatch.features.watches.WatchesSectionRoute

/**
 * The Home destination, and the list side of the list-detail layout on a wide screen. It has no
 * state of its own: it fills [HomeScreen]'s watch, scale and device slots with their section
 * routes. [selectedDeviceId] is the device shown beside the list, marked in it.
 */
@Composable
fun HomeRoute(
    onOpenWatch: (String) -> Unit,
    onOpenScale: (deviceId: String) -> Unit,
    onOpenDevice: (deviceId: String) -> Unit,
    onOpenHealthConnect: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    selectedDeviceId: String? = null,
) {
    HomeScreen(
        onOpenHealthConnect = onOpenHealthConnect,
        onOpenSettings = onOpenSettings,
        watchesSection = { WatchesSectionRoute(onOpenWatch = onOpenWatch, selectedDeviceId = selectedDeviceId) },
        scalesSection = { ScalesSectionRoute(onOpenScale = onOpenScale, selectedDeviceId = selectedDeviceId) },
        devicesSection = { DevicesSectionRoute(onOpenDevice = onOpenDevice, selectedDeviceId = selectedDeviceId) },
        modifier = modifier,
    )
}
