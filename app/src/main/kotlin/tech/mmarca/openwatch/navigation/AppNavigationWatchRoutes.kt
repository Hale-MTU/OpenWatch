package tech.mmarca.openwatch.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import tech.mmarca.openwatch.features.watches.WatchAlarmsRoute
import tech.mmarca.openwatch.features.watches.WatchDataRoute
import tech.mmarca.openwatch.features.watches.WatchDeviceRoute
import tech.mmarca.openwatch.features.watches.WatchNotificationAppsRoute
import tech.mmarca.openwatch.features.watches.WatchSendPointRoute
import tech.mmarca.openwatch.features.watches.WatchSettingsRoute

/** The watch-facing destinations. Plain pushed screens. */
internal fun NavGraphBuilder.watchRoutes(
    navController: NavHostController,
) {
    composable(Screen.WatchDevice.route) {
        WatchDeviceRoute(
            onOpenData = { deviceId ->
                navController.navigate(Screen.WatchData.createRoute(deviceId)) {
                    launchSingleTop = true
                }
            },
            onOpenNotifications = { deviceId ->
                navController.navigate(Screen.WatchNotifications.createRoute(deviceId)) {
                    launchSingleTop = true
                }
            },
            onOpenWatchSettings = { deviceId, screenId ->
                navController.navigate(Screen.WatchSettings.createRoute(deviceId, screenId)) {
                    launchSingleTop = true
                }
            },
            onOpenSendPoint = { deviceId ->
                navController.navigate(Screen.WatchSendPoint.createRoute(watchDeviceId = deviceId)) {
                    launchSingleTop = true
                }
            },
            onOpenAlarms = { deviceId ->
                navController.navigate(Screen.WatchAlarms.createRoute(deviceId)) {
                    launchSingleTop = true
                }
            },
            onRemoved = { navController.popBackStack() },
        )
    }

    composable(Screen.WatchData.route) {
        WatchDataRoute()
    }

    composable(Screen.WatchNotifications.route) {
        WatchNotificationAppsRoute()
    }

    composable(Screen.WatchAlarms.route) {
        WatchAlarmsRoute()
    }

    // Strings, so an absent position stays absent: a number argument cannot be null.
    composable(
        route = Screen.WatchSendPoint.route,
        arguments = listOf(
            WATCH_DEVICE_ID_ARG,
            WATCH_POINT_LATITUDE_ARG,
            WATCH_POINT_LONGITUDE_ARG,
            WATCH_POINT_NAME_ARG,
            WATCH_POINT_UNREADABLE_ARG,
        ).map { name ->
            navArgument(name) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }
        },
    ) {
        WatchSendPointRoute(
            onOpenWatches = {
                navController.navigate(Screen.Home.route) { launchSingleTop = true }
            },
        )
    }

    // A row that leads deeper pushes the same route with another screen id;
    // the screens share one held link.
    composable(Screen.WatchSettings.route) {
        WatchSettingsRoute(
            onOpenSubscreen = { deviceId, screenId ->
                navController.navigate(Screen.WatchSettings.createRoute(deviceId, screenId))
            },
            onClose = { navController.popBackStack() },
        )
    }
}
