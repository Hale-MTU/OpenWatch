package tech.mmarca.openwatch.wear.features.dashboard

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.wear.compose.navigation.composable
import tech.mmarca.openwatch.wear.MetricTile
import tech.mmarca.openwatch.wear.WearRoutes
import tech.mmarca.openwatch.wear.WearUiState

/** What the tile editor does with the dashboard's chosen tiles: a change of them, and back to the defaults. */
internal class TileChoice(
    val edit: ((List<MetricTile>) -> List<MetricTile>) -> Unit,
    val reset: () -> Unit,
)

/**
 * The dashboard, the start destination: the tiles the wearer chose, each
 * opening its metric, the spot measurements where the watch can take one,
 * the quick log, the workout ([onStartActivity]), the tile editor and the
 * status. And the tile editor, `tile_editor`, whose changes go to [tiles].
 * [state] is read at each composition, so the screens show the newest.
 */
internal fun NavGraphBuilder.dashboardDestinations(
    navController: NavHostController,
    state: () -> WearUiState,
    tiles: TileChoice,
    onStartActivity: () -> Unit,
) {
    composable(WearRoutes.DASHBOARD) {
        val current = state()
        DashboardScreen(
            tiles = current.shownTiles,
            metrics = current.metrics,
            unitSystem = current.preferences.unitSystem,
            canMeasure = current.sensors?.spotKinds.orEmpty().isNotEmpty(),
            // A second tap before the screen is up must not push it twice.
            onOpenMetric = { navController.navigate(WearRoutes.metric(it)) { launchSingleTop = true } },
            onMeasure = { navController.navigate(WearRoutes.MEASURE) { launchSingleTop = true } },
            onQuickLog = { navController.navigate(WearRoutes.QUICK_LOG) { launchSingleTop = true } },
            onEditTiles = { navController.navigate(WearRoutes.TILE_EDITOR) { launchSingleTop = true } },
            onStartActivity = onStartActivity,
            onOpenStatus = { navController.navigate(WearRoutes.STATUS) },
        )
    }
    composable(WearRoutes.TILE_EDITOR) {
        val current = state()
        TileEditorScreen(
            available = current.availableMetrics,
            tiles = current.tiles,
            onEdit = tiles.edit,
            onReset = tiles.reset,
        )
    }
}
