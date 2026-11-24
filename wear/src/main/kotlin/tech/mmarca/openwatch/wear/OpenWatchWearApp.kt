package tech.mmarca.openwatch.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import tech.mmarca.openwatch.wear.features.dashboard.dashboardDestination
import tech.mmarca.openwatch.wear.features.metric.metricDestination
import tech.mmarca.openwatch.wear.features.quicklog.quickLogDestinations
import tech.mmarca.openwatch.wear.features.recording.workoutDestination
import tech.mmarca.openwatch.wear.features.status.statusDestination
import tech.mmarca.openwatch.wear.ui.theme.OpenWatchWearTheme
import tech.mmarca.openwatch.wearlink.WearLinkEntry

/**
 * The whole watch UI: theme, scaffold and navigation. It renders [state] and
 * hands what the wearer logs to [onLog]; nothing here reads a sensor. Each
 * feature declares its destinations in its own package; the dashboard opens
 * a metric, the quick log, the workout and the link and recorder status
 * ([statusScreen]).
 *
 * A running workout, or a saved one whose summary is still open, brings the
 * workout forward when the app opens ([workoutActive]) and when the wearer
 * asks for it from outside the app, as from the watch face pill
 * ([workoutRequests]). Swiping it away while it runs leaves the recording
 * running; the dashboard's button opens it again.
 */
@Composable
fun OpenWatchWearApp(
    state: WearUiState,
    workoutActive: Boolean = false,
    /** Bumped each time the workout is asked for from outside the app. */
    workoutRequests: Int = 0,
    /** Logs a value entered by hand now: its type, its value and the second value of a pair. */
    onLog: (type: WearLinkEntry.Type, value: Double, second: Double?) -> Unit = { _, _, _ -> },
    /** Link and recorder status, permissions and paired phones; it can open the workout. */
    statusScreen: @Composable (onOpenWorkout: () -> Unit) -> Unit = {},
) {
    // The nav graph is built once, so every destination reads the latest
    // state through this holder instead of a value captured at build time.
    val currentState by rememberUpdatedState(state)
    val currentLog by rememberUpdatedState(onLog)

    OpenWatchWearTheme {
        // AppScaffold draws the time once, above every screen.
        AppScaffold {
            val navController = rememberSwipeDismissableNavController()
            val openWorkout = { navController.navigate(WearRoutes.WORKOUT) { launchSingleTop = true } }
            SwipeDismissableNavHost(
                navController = navController,
                startDestination = WearRoutes.DASHBOARD,
            ) {
                dashboardDestination(navController, { currentState }, onStartActivity = openWorkout)
                metricDestination(navController) { currentState }
                quickLogDestinations(navController, { currentState }) { type, value, second -> currentLog(type, value, second) }
                statusDestination { statusScreen(openWorkout) }
                workoutDestination(navController) { currentState }
            }
            LaunchedEffect(workoutActive, workoutRequests) {
                if (workoutActive && navController.currentDestination?.route != WearRoutes.WORKOUT) openWorkout()
            }
        }
    }
}
