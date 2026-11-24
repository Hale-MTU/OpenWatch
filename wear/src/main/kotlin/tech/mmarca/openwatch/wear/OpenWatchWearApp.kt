package tech.mmarca.openwatch.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import tech.mmarca.openwatch.wear.features.dashboard.TileChoice
import tech.mmarca.openwatch.wear.features.dashboard.dashboardDestinations
import tech.mmarca.openwatch.wear.features.measure.measureDestinations
import tech.mmarca.openwatch.wear.features.metric.metricDestination
import tech.mmarca.openwatch.wear.features.quicklog.quickLogDestinations
import tech.mmarca.openwatch.wear.features.recording.workoutDestination
import tech.mmarca.openwatch.wear.features.settings.settingsDestinations
import tech.mmarca.openwatch.wear.features.status.statusDestination
import tech.mmarca.openwatch.wear.ui.theme.OpenWatchWearTheme
import tech.mmarca.openwatch.wearlink.WearLinkEntry

/**
 * The whole watch UI: theme, scaffold and navigation. It renders [state],
 * hands what the wearer logs to [onLog], the tiles the wearer chooses to
 * [onEditTiles], and runs a spot measurement through [spot]; nothing here
 * reads a sensor. Each feature declares its destinations in its own package;
 * the dashboard opens a metric, the spot measurements, the quick log, the
 * workout, the tile editor and the settings, which lead on to the phone, the
 * sensor access and the link and recorder status. What only the activity
 * can do, ask for a grant or open the Bluetooth settings, comes in
 * [actions].
 *
 * A running workout, or a saved one whose summary is still open, brings the
 * workout forward when the app opens ([workoutActive]) and when the wearer
 * asks for it from outside the app, as from the watch face pill
 * ([workoutRequests]). Swiping it away while it runs leaves the recording
 * running; the dashboard's button opens it again. A screen a tile asks for
 * ([deepLink]) opens over the dashboard, unless a workout takes the screen:
 * the workout wins, and the request is dropped.
 */
@Composable
fun OpenWatchWearApp(
    state: WearUiState,
    workoutActive: Boolean = false,
    /** Bumped each time the workout is asked for from outside the app. */
    workoutRequests: Int = 0,
    /** A screen a tile asked for, `MainActivity.EXTRA_ROUTE`; null for none. Opened once, then [onDeepLinkHandled]. */
    deepLink: String? = null,
    onDeepLinkHandled: () -> Unit = {},
    /** Logs a value entered by hand now: its type, its value and the second value of a pair. */
    onLog: (type: WearLinkEntry.Type, value: Double, second: Double?) -> Unit = { _, _, _ -> },
    /** Changes the dashboard's chosen tiles; the change gets them as they are when it runs. */
    onEditTiles: ((List<MetricTile>) -> List<MetricTile>) -> Unit = {},
    onResetTiles: () -> Unit = {},
    /** Runs one spot measurement (`WearGraph.spot`), which keeps its reading. */
    spot: (SpotKind) -> Flow<SpotEvent> = { emptyFlow() },
    actions: ActivityActions = ActivityActions.NONE,
) {
    // The nav graph is built once, so every destination reads the latest
    // state through this holder instead of a value captured at build time.
    val currentState by rememberUpdatedState(state)
    val currentLog by rememberUpdatedState(onLog)
    val currentSpot by rememberUpdatedState(spot)
    val currentEditTiles by rememberUpdatedState(onEditTiles)
    val currentResetTiles by rememberUpdatedState(onResetTiles)
    val tiles = remember { TileChoice(edit = { change -> currentEditTiles(change) }, reset = { currentResetTiles() }) }

    OpenWatchWearTheme {
        // AppScaffold draws the time once, above every screen.
        AppScaffold {
            val navController = rememberSwipeDismissableNavController()
            val openWorkout = { navController.navigate(WearRoutes.WORKOUT) { launchSingleTop = true } }
            SwipeDismissableNavHost(
                navController = navController,
                startDestination = WearRoutes.DASHBOARD,
            ) {
                dashboardDestinations(navController, { currentState }, tiles, onStartActivity = openWorkout)
                metricDestination(navController) { currentState }
                measureDestinations(navController, { currentState }) { kind -> currentSpot(kind) }
                quickLogDestinations(navController, { currentState }) { type, value, second -> currentLog(type, value, second) }
                settingsDestinations(navController, actions)
                statusDestination(actions, onOpenWorkout = openWorkout)
                workoutDestination(navController) { currentState }
            }
            LaunchedEffect(workoutActive, workoutRequests) {
                if (workoutActive && navController.currentDestination?.route != WearRoutes.WORKOUT) openWorkout()
            }
            LaunchedEffect(deepLink) {
                val route = deepLink ?: return@LaunchedEffect
                // The workout wins; a route this build does not let a tile open is ignored.
                if (!workoutActive && WearRoutes.isDeepLinkable(route)) {
                    navController.navigate(route) {
                        popUpTo(WearRoutes.DASHBOARD)
                        launchSingleTop = true
                    }
                }
                onDeepLinkHandled()
            }
        }
    }
}
