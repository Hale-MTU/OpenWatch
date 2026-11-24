package tech.mmarca.openwatch.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import tech.mmarca.openwatch.wear.features.dashboard.DashboardScreen
import tech.mmarca.openwatch.wear.features.metric.MetricDetailScreen
import tech.mmarca.openwatch.wear.ui.theme.OpenWatchWearTheme

/**
 * The whole watch UI: theme, scaffold and navigation. It renders [state];
 * nothing here reads a sensor. The dashboard opens the workout
 * ([workoutScreen]) and the link and recorder status ([statusScreen]).
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
    /** Link and recorder status, permissions and paired phones; it can open the workout. */
    statusScreen: @Composable (onOpenWorkout: () -> Unit) -> Unit = {},
    /** The workout, which closes itself when the wearer leaves the picker or the summary. */
    workoutScreen: @Composable (onClose: () -> Unit) -> Unit = {},
) {
    // The nav graph is built once, so every destination reads the latest
    // state through this holder instead of a value captured at build time.
    val currentState by rememberUpdatedState(state)

    OpenWatchWearTheme {
        // AppScaffold draws the time once, above every screen.
        AppScaffold {
            val navController = rememberSwipeDismissableNavController()
            val openWorkout = { navController.navigate(WearRoutes.WORKOUT) { launchSingleTop = true } }
            SwipeDismissableNavHost(
                navController = navController,
                startDestination = WearRoutes.DASHBOARD,
            ) {
                composable(WearRoutes.DASHBOARD) {
                    DashboardScreen(
                        tiles = MetricTile.DefaultTiles.filter { it in currentState.availableMetrics },
                        metrics = currentState.metrics,
                        unitSystem = currentState.preferences.unitSystem,
                        // A second tap before the screen is up must not push it twice.
                        onOpenMetric = { navController.navigate(WearRoutes.metric(it)) { launchSingleTop = true } },
                        onStartActivity = openWorkout,
                        onOpenStatus = { navController.navigate(WearRoutes.STATUS) },
                    )
                }
                composable(WearRoutes.METRIC) { entry ->
                    val metric = WearRoutes.metricOf(entry.arguments?.getString(WearRoutes.METRIC_ARG))
                    if (metric == null) {
                        // A name this build has no metric for: back to the dashboard, the start destination.
                        LaunchedEffect(Unit) { navController.popBackStack(WearRoutes.DASHBOARD, inclusive = false) }
                    } else {
                        MetricDetailScreen(
                            metric = metric,
                            state = currentState.metrics[metric] ?: MetricUiState(),
                            unitSystem = currentState.preferences.unitSystem,
                            restingHeartRate = currentState.metrics[MetricTile.RESTING_HEART_RATE]?.current,
                            sleepStages = currentState.sleepStages,
                        )
                    }
                }
                composable(WearRoutes.STATUS) { statusScreen(openWorkout) }
                composable(WearRoutes.WORKOUT) { workoutScreen { navController.popBackStack() } }
            }
            LaunchedEffect(workoutActive, workoutRequests) {
                if (workoutActive && navController.currentDestination?.route != WearRoutes.WORKOUT) openWorkout()
            }
        }
    }
}
