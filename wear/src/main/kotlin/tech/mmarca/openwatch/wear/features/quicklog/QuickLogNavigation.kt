package tech.mmarca.openwatch.wear.features.quicklog

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.wear.compose.navigation.composable
import kotlin.math.roundToInt
import tech.mmarca.openwatch.wear.MetricTile
import tech.mmarca.openwatch.wear.WearRoutes
import tech.mmarca.openwatch.wear.WearUiState
import tech.mmarca.openwatch.wearlink.WearLinkEntry

/** Logs a value entered by hand now: its type, its value and the second value of a pair, in the type's unit. */
internal typealias LogEntry = (type: WearLinkEntry.Type, value: Double, second: Double?) -> Unit

/** The weight stepper's start before anything was logged, kilograms. */
private const val DefaultWeightKg = 70.0

/** A cuff's usual reading, where the blood pressure steppers start before anything was logged. */
private const val DefaultSystolic = 120
private const val DefaultDiastolic = 80

/**
 * The quick log, `quick_log`, and the entries it opens: the weight and the
 * blood pressure steppers, `log_weight` and `log_blood_pressure`, and the
 * breathing minute, `breathe`. Every entry goes to [log]; the steppers start
 * from the last value logged.
 */
internal fun NavGraphBuilder.quickLogDestinations(navController: NavHostController, state: () -> WearUiState, log: LogEntry) {
    composable(WearRoutes.QUICK_LOG) {
        QuickLogScreen(
            state = state(),
            onAddWater = { log(WearLinkEntry.Type.WATER, WaterServingMl, null) },
            onLogWeight = { navController.navigate(WearRoutes.LOG_WEIGHT) { launchSingleTop = true } },
            onLogBloodPressure = { navController.navigate(WearRoutes.LOG_BLOOD_PRESSURE) { launchSingleTop = true } },
            onBreathe = { navController.navigate(WearRoutes.BREATHE) { launchSingleTop = true } },
        )
    }
    composable(WearRoutes.LOG_WEIGHT) {
        val current = state()
        WeightEntryScreen(
            initialKg = current.metrics[MetricTile.WEIGHT]?.current ?: DefaultWeightKg,
            unitSystem = current.preferences.unitSystem,
            onSave = { kilograms ->
                log(WearLinkEntry.Type.WEIGHT, kilograms, null)
                navController.popBackStack()
            },
        )
    }
    composable(WearRoutes.LOG_BLOOD_PRESSURE) {
        val last = state().entries.lastOrNull { it.type == WearLinkEntry.Type.BLOOD_PRESSURE }
        BloodPressureEntryScreen(
            initialSystolic = last?.value?.roundToInt() ?: DefaultSystolic,
            initialDiastolic = last?.second?.roundToInt() ?: DefaultDiastolic,
            onSave = { systolic, diastolic ->
                log(WearLinkEntry.Type.BLOOD_PRESSURE, systolic.toDouble(), diastolic.toDouble())
                navController.popBackStack()
            },
        )
    }
    composable(WearRoutes.BREATHE) {
        BreatheScreen(
            onFinished = { log(WearLinkEntry.Type.MINDFULNESS, BreatheSessionMinutes, null) },
            onClose = { navController.popBackStack() },
        )
    }
}
