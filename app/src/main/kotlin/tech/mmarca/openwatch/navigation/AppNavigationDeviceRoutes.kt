package tech.mmarca.openwatch.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import tech.mmarca.openwatch.features.device.DeviceDetailRoute
import tech.mmarca.openwatch.features.device.DeviceScreenHost
import tech.mmarca.openwatch.features.watches.WatchAlarmsRoute
import tech.mmarca.openwatch.features.watches.WatchDataRoute
import tech.mmarca.openwatch.features.watches.WatchNotificationAppsRoute
import tech.mmarca.openwatch.features.watches.WatchSendPointRoute
import tech.mmarca.openwatch.features.watches.WatchSettingsRoute

/**
 * A paired device and the screens its family opens from it. Plain pushed screens: a family's
 * panel or screen names its own routes (the Garmin watch's data, notifications, settings tree,
 * point and alarms), and they are declared here, where the graph is.
 */
internal fun NavGraphBuilder.deviceRoutes(
    navController: NavHostController,
) {
    composable<DeviceDetail> {
        DeviceDetailRoute(
            host = DeviceScreenHost(
                navigate = { route -> navController.navigate(route) { launchSingleTop = true } },
                onOpenBodyProfile = { navController.navigate(BodyProfile) { launchSingleTop = true } },
                onRemoved = { navController.popBackStack() },
            ),
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
