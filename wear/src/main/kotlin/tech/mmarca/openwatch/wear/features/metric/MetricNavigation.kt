package tech.mmarca.openwatch.wear.features.metric

import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.wear.compose.navigation.composable
import tech.mmarca.openwatch.wear.MetricTile
import tech.mmarca.openwatch.wear.MetricUiState
import tech.mmarca.openwatch.wear.SpotKind
import tech.mmarca.openwatch.wear.WearRoutes
import tech.mmarca.openwatch.wear.WearUiState

/**
 * A metric's detail, `metric/{metric}`, which a dashboard tile opens. The
 * heart rate and the HRV offer their spot measurement where the watch can
 * take it.
 */
internal fun NavGraphBuilder.metricDestination(navController: NavHostController, state: () -> WearUiState) {
    composable(WearRoutes.METRIC) { entry ->
        val metric = WearRoutes.metricOf(entry.arguments?.getString(WearRoutes.METRIC_ARG))
        if (metric == null) {
            // A name this build has no metric for: back to the dashboard, the start destination.
            LaunchedEffect(Unit) { navController.popBackStack(WearRoutes.DASHBOARD, inclusive = false) }
        } else {
            val current = state()
            val spot = metric.spotKind?.takeIf { kind -> current.sensors?.spotKinds.orEmpty().contains(kind) }
            MetricDetailScreen(
                metric = metric,
                state = current.metrics[metric] ?: MetricUiState(),
                unitSystem = current.preferences.unitSystem,
                restingHeartRate = current.metrics[MetricTile.RESTING_HEART_RATE]?.current,
                sleepStages = current.sleepStages,
                onMeasure = spot?.let { kind -> { navController.navigate(WearRoutes.measurement(kind)) { launchSingleTop = true } } },
                entries = current.entries,
            )
        }
    }
}

/** The spot measurement that gives this metric a value now, if there is one. */
private val MetricTile.spotKind: SpotKind?
    get() = when (this) {
        MetricTile.HEART_RATE -> SpotKind.HEART_RATE
        MetricTile.HRV -> SpotKind.HRV
        else -> null
    }
