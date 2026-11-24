package tech.mmarca.openwatch.wear.features.measure

import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.wear.compose.navigation.composable
import kotlinx.coroutines.flow.Flow
import tech.mmarca.openwatch.wear.SpotEvent
import tech.mmarca.openwatch.wear.SpotKind
import tech.mmarca.openwatch.wear.WearRoutes
import tech.mmarca.openwatch.wear.WearUiState

/**
 * The spot measurements: the list, `measure`, which the dashboard opens, and
 * one measurement, `measure/{measurement}`, which the list and a metric's
 * detail open. Each runs through [spot] (`WearGraph.spot`).
 */
internal fun NavGraphBuilder.measureDestinations(
    navController: NavHostController,
    state: () -> WearUiState,
    spot: (SpotKind) -> Flow<SpotEvent>,
) {
    composable(WearRoutes.MEASURE) {
        MeasureListScreen(
            sensors = state().sensors,
            onMeasure = { navController.navigate(WearRoutes.measurement(it.kind)) { launchSingleTop = true } },
        )
    }
    composable(WearRoutes.MEASUREMENT) { entry ->
        val kind = WearRoutes.spotKindOf(entry.arguments?.getString(WearRoutes.MEASUREMENT_ARG))
        if (kind == null) {
            // A name this build has no measurement for: back to the dashboard, the start destination.
            LaunchedEffect(Unit) { navController.popBackStack(WearRoutes.DASHBOARD, inclusive = false) }
        } else {
            val current = state()
            MeasurementRoute(
                measurement = Measurement.of(kind),
                supported = current.sensors?.let { kind in it.spotKinds },
                unitSystem = current.preferences.unitSystem,
                events = spot,
                onDone = { navController.popBackStack() },
            )
        }
    }
}
