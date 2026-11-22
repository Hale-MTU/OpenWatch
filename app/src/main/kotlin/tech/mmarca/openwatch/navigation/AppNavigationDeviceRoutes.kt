package tech.mmarca.openwatch.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import tech.mmarca.openwatch.features.device.DeviceDetailRoute
import tech.mmarca.openwatch.features.watches.WatchAlarmsRoute
import tech.mmarca.openwatch.features.watches.WatchDataRoute
import tech.mmarca.openwatch.features.watches.WatchNotificationAppsRoute
import tech.mmarca.openwatch.features.watches.WatchSendPointRoute
import tech.mmarca.openwatch.features.watches.WatchSettingsRoute

/** A paired device and the screens a watch opens from it. Plain pushed screens. */
internal fun NavGraphBuilder.deviceRoutes(
    navController: NavHostController,
) {
    composable<DeviceDetail> {
        DeviceDetailRoute(
            onOpenData = { deviceId ->
                navController.navigate(WatchData(deviceId)) {
                    launchSingleTop = true
                }
            },
            onOpenNotifications = { deviceId ->
                navController.navigate(WatchNotifications(deviceId)) {
                    launchSingleTop = true
                }
            },
            onOpenWatchSettings = { deviceId, screenId ->
                navController.navigate(WatchSettings(deviceId, screenId)) {
                    launchSingleTop = true
                }
            },
            onOpenSendPoint = { deviceId ->
                navController.navigate(WatchSendPoint(deviceId = deviceId)) {
                    launchSingleTop = true
                }
            },
            onOpenAlarms = { deviceId ->
                navController.navigate(WatchAlarms(deviceId)) {
                    launchSingleTop = true
                }
            },
            onOpenBodyProfile = { navController.navigate(BodyProfile) { launchSingleTop = true } },
            onRemoved = { navController.popBackStack() },
        )
    }

    composable<WatchData> {
        WatchDataRoute()
    }

    composable<WatchNotifications> {
        WatchNotificationAppsRoute()
    }

    composable<WatchAlarms> {
        WatchAlarmsRoute()
    }

    composable<WatchSendPoint> {
        WatchSendPointRoute(
            onOpenWatches = {
                navController.navigate(Home) { launchSingleTop = true }
            },
        )
    }

    // A row that leads deeper pushes the same route with another screen id;
    // the screens share one held link.
    composable<WatchSettings> {
        WatchSettingsRoute(
            onOpenSubscreen = { deviceId, screenId ->
                navController.navigate(WatchSettings(deviceId, screenId))
            },
            onClose = { navController.popBackStack() },
        )
    }
}
