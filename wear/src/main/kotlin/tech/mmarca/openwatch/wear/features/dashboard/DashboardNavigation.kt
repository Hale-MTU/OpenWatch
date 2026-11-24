package tech.mmarca.openwatch.wear.features.dashboard

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.wear.compose.navigation.composable
import tech.mmarca.openwatch.wear.MetricTile
import tech.mmarca.openwatch.wear.WearRoutes
import tech.mmarca.openwatch.wear.WearUiState

/**
 * The dashboard, the start destination: today's tiles, each opening its
 * metric, the quick log, the workout ([onStartActivity]) and the status.
 * [state] is read at each composition, so the screen shows the newest.
 */
internal fun NavGraphBuilder.dashboardDestination(
    navController: NavHostController,
    state: () -> WearUiState,
    onStartActivity: () -> Unit,
) {
    composable(WearRoutes.DASHBOARD) {
        val current = state()
        DashboardScreen(
            tiles = MetricTile.DefaultTiles.filter { it in current.availableMetrics },
            metrics = current.metrics,
            unitSystem = current.preferences.unitSystem,
            // A second tap before the screen is up must not push it twice.
            onOpenMetric = { navController.navigate(WearRoutes.metric(it)) { launchSingleTop = true } },
            onQuickLog = { navController.navigate(WearRoutes.QUICK_LOG) { launchSingleTop = true } },
            onStartActivity = onStartActivity,
            onOpenStatus = { navController.navigate(WearRoutes.STATUS) },
        )
    }
}
