package tech.mmarca.openwatch.wear.features.quicklog

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.AppScaffold
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.UnitSystem
import tech.mmarca.openwatch.wear.ui.preview.WearPreviews
import tech.mmarca.openwatch.wear.ui.theme.OpenWatchWearTheme
import tech.mmarca.openwatch.wearlink.WearLinkEntry.Type

/**
 * A value the watch logs by stepping to it. [metricRange] and [metricDefault]
 * are in the metric unit, the entry's ([Type]); the imperial unit is a
 * straight line from it, `display = metric * imperialScale + imperialOffset`,
 * so a value is stored in the metric unit whichever is shown. Each range
 * stays within what Health Connect stores, so an entry the phone cannot write
 * is never made.
 */
internal data class LogValueSpec(
    @param:StringRes val label: Int,
    @param:StringRes val metricUnit: Int,
    @param:StringRes val imperialUnit: Int,
    val metricRange: ClosedFloatingPointRange<Double>,
    val metricDefault: Double,
    /** Digits after the point in the metric unit; one stepper step is one unit in this place. */
    val decimals: Int,
    val imperialScale: Double = 1.0,
    val imperialOffset: Double = 0.0,
    /** Digits after the point in the imperial unit, where they differ. */
    val imperialDecimals: Int = decimals,
) {
    @StringRes
    fun unit(unitSystem: UnitSystem): Int = if (unitSystem == UnitSystem.IMPERIAL) imperialUnit else metricUnit

    fun toDisplay(metric: Double, unitSystem: UnitSystem): Double =
        if (unitSystem == UnitSystem.IMPERIAL) metric * imperialScale + imperialOffset else metric

    fun toMetric(display: Double, unitSystem: UnitSystem): Double =
        if (unitSystem == UnitSystem.IMPERIAL) (display - imperialOffset) / imperialScale else display

    /** Digits after the point the stepper shows in [unitSystem]. */
    fun decimals(unitSystem: UnitSystem): Int = if (unitSystem == UnitSystem.IMPERIAL) imperialDecimals else decimals

    /** Stepper steps per displayed unit: 10 for one decimal. */
    fun stepsPerUnit(unitSystem: UnitSystem): Int = 10.0.pow(decimals(unitSystem)).roundToInt()

    fun format(display: Double, unitSystem: UnitSystem): String =
        String.format(Locale.getDefault(), "%.${decimals(unitSystem)}f", display)

    /** [display] stepped to in [unitSystem], in the metric unit, to the metric unit's own decimals. */
    fun stored(display: Double, unitSystem: UnitSystem): Double {
        val perUnit = 10.0.pow(decimals)
        return (toMetric(display, unitSystem) * perUnit).roundToInt() / perUnit
    }
}

/** The values the quick log takes one number of, in the order it lists them. */
internal val LogValueSpecs: Map<Type, LogValueSpec> = linkedMapOf(
    Type.BODY_FAT to LogValueSpec(
        label = R.string.metric_body_fat,
        metricUnit = R.string.unit_percent,
        imperialUnit = R.string.unit_percent,
        metricRange = 3.0..60.0,
        metricDefault = 20.0,
        decimals = 1,
    ),
    Type.BLOOD_OXYGEN to LogValueSpec(
        label = R.string.metric_blood_oxygen,
        metricUnit = R.string.unit_percent,
        imperialUnit = R.string.unit_percent,
        metricRange = 70.0..100.0,
        metricDefault = 97.0,
        decimals = 0,
    ),
    Type.RESPIRATORY_RATE to LogValueSpec(
        label = R.string.metric_respiratory_rate,
        metricUnit = R.string.unit_breaths_per_minute,
        imperialUnit = R.string.unit_breaths_per_minute,
        metricRange = 4.0..60.0,
        metricDefault = 14.0,
        decimals = 0,
    ),
    Type.BODY_TEMPERATURE to LogValueSpec(
        label = R.string.metric_body_temperature,
        metricUnit = R.string.unit_celsius,
        imperialUnit = R.string.unit_fahrenheit,
        metricRange = 34.0..42.0,
        metricDefault = 36.5,
        decimals = 1,
        imperialScale = 1.8,
        imperialOffset = 32.0,
    ),
    Type.BLOOD_GLUCOSE to LogValueSpec(
        label = R.string.metric_blood_glucose,
        metricUnit = R.string.unit_mmol_l,
        imperialUnit = R.string.unit_mg_dl,
        metricRange = 2.0..30.0,
        metricDefault = 5.5,
        decimals = 1,
        imperialScale = MG_DL_PER_MMOL_L,
        // mg/dL is read in whole numbers.
        imperialDecimals = 0,
    ),
)

/** Glucose: one mmol/L is 18.0182 mg/dL. */
private const val MG_DL_PER_MMOL_L = 18.0182

/**
 * One value of [type], picked with the stepper or the crown in [unitSystem].
 * It starts from [initialMetric], the last one logged, or the type's default,
 * and is saved in the metric unit.
 */
@Composable
internal fun LogValueScreen(
    type: Type,
    initialMetric: Double?,
    unitSystem: UnitSystem,
    onSave: (metric: Double) -> Unit,
) {
    val spec = LogValueSpecs.getValue(type)
    val perUnit = spec.stepsPerUnit(unitSystem)
    val min = (spec.toDisplay(spec.metricRange.start, unitSystem) * perUnit).roundToInt()
    val max = (spec.toDisplay(spec.metricRange.endInclusive, unitSystem) * perUnit).roundToInt()
    val start = spec.toDisplay(initialMetric ?: spec.metricDefault, unitSystem)
    var steps by rememberSaveable { mutableIntStateOf((start * perUnit).roundToInt().coerceIn(min, max)) }
    ValueStepper(
        title = stringResource(spec.label),
        unit = stringResource(spec.unit(unitSystem)),
        value = steps,
        onValueChange = { steps = it },
        range = min..max,
        format = { spec.format(it.toDouble() / perUnit, unitSystem) },
        onConfirm = { onSave(spec.stored(steps.toDouble() / perUnit, unitSystem)) },
    )
}

@WearPreviews
@Composable
private fun LogValueScreenPreview() {
    OpenWatchWearTheme {
        AppScaffold {
            LogValueScreen(type = Type.BODY_TEMPERATURE, initialMetric = 36.6, unitSystem = UnitSystem.METRIC, onSave = {})
        }
    }
}
