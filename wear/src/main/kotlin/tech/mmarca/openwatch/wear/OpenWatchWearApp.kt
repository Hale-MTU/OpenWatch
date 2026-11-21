package tech.mmarca.openwatch.wear

import androidx.compose.runtime.Composable
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
 * nothing here reads a sensor. The dashboard opens the workout recorder's own
 * screen ([onStartWorkout]) and the link and recorder status ([statusScreen]).
 */
@Composable
fun OpenWatchWearApp(
    state: WearUiState,
    /** Opens the workout recorder's own screen, which picks the sport. */
    onStartWorkout: () -> Unit = {},
    /** Link and recorder status, permissions and paired phones; the activity owns its stores. */
    statusScreen: @Composable () -> Unit = {},
) {
    // The nav graph is built once, so every destination reads the latest
    // state through this holder instead of a value captured at build time.
    val currentState by rememberUpdatedState(state)

    OpenWatchWearTheme {
        // AppScaffold draws the time once, above every screen.
        AppScaffold {
            val navController = rememberSwipeDismissableNavController()
            SwipeDismissableNavHost(
                navController = navController,
                startDestination = WearRoutes.DASHBOARD,
            ) {
                composable(WearRoutes.DASHBOARD) {
                    DashboardScreen(
                        tiles = MetricTile.DefaultTiles.filter { it in currentState.availableMetrics },
                        metrics = currentState.metrics,
                        unitSystem = currentState.preferences.unitSystem,
                        onOpenMetric = { navController.navigate(WearRoutes.metric(it)) },
                        onStartActivity = onStartWorkout,
                        onOpenStatus = { navController.navigate(WearRoutes.STATUS) },
                    )
                }
                composable(WearRoutes.METRIC) { entry ->
                    val metric = MetricTile.valueOf(checkNotNull(entry.arguments?.getString(WearRoutes.METRIC_ARG)))
                    MetricDetailScreen(
                        metric = metric,
                        state = currentState.metrics[metric] ?: MetricUiState(),
                        unitSystem = currentState.preferences.unitSystem,
                        maxHeartRate = currentState.preferences.maxHeartRate,
                        restingHeartRate = currentState.metrics[MetricTile.RESTING_HEART_RATE]?.current,
                        sleepStages = currentState.sleepStages,
                    )
                }
                composable(WearRoutes.STATUS) { statusScreen() }
            }
        }
    }
}
