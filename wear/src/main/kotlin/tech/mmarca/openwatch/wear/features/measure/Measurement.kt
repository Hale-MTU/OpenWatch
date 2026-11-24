package tech.mmarca.openwatch.wear.features.measure

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.math.roundToInt
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.SpotFailure
import tech.mmarca.openwatch.wear.SpotKind
import tech.mmarca.openwatch.wear.UnitSystem
import tech.mmarca.openwatch.wear.ui.components.FeetPerMeter
import tech.mmarca.openwatch.wear.ui.components.formatCount
import tech.mmarca.openwatch.wear.ui.theme.ElevationColor
import tech.mmarca.openwatch.wear.ui.theme.HeartColor
import tech.mmarca.openwatch.wear.ui.theme.VitalsColor

/**
 * A spot measurement ([SpotKind]) as the screens show it: its name, what to
 * do, its icon and colour, and the units of its live value and its result.
 * Offered only where the watch has its sensor (`WatchSensors.spotKinds`).
 */
enum class Measurement(
    val kind: SpotKind,
    @param:StringRes val label: Int,
    @param:StringRes val description: Int,
    @param:StringRes val instruction: Int,
    val icon: ImageVector,
    val accentColor: Color,
    /** Needs the heart rate grant before it can start. */
    val needsHeartRatePermission: Boolean,
    /** The unit of the value shown while it runs. */
    @param:StringRes val liveUnit: Int,
    /** The unit of the result. */
    @param:StringRes val unit: Int,
) {
    HEART_RATE(
        SpotKind.HEART_RATE,
        R.string.measure_heart_rate,
        R.string.measure_heart_rate_description,
        R.string.measure_instruction_still,
        Icons.Outlined.Favorite,
        HeartColor,
        needsHeartRatePermission = true,
        liveUnit = R.string.unit_bpm,
        unit = R.string.unit_bpm,
    ),

    /** While it runs it shows the pulse its beats stand for; the result is the RMSSD. */
    HRV(
        SpotKind.HRV,
        R.string.measure_hrv,
        R.string.measure_hrv_description,
        R.string.measure_instruction_still_minute,
        Icons.Outlined.Insights,
        VitalsColor,
        needsHeartRatePermission = true,
        liveUnit = R.string.unit_bpm,
        unit = R.string.unit_ms,
    ),

    AIR_PRESSURE(
        SpotKind.AIR_PRESSURE,
        R.string.measure_air_pressure,
        R.string.measure_air_pressure_description,
        R.string.measure_instruction_none,
        Icons.Outlined.Compress,
        ElevationColor,
        needsHeartRatePermission = false,
        liveUnit = R.string.unit_hpa,
        unit = R.string.unit_hpa,
    ),
    ;

    companion object {
        fun of(kind: SpotKind): Measurement = entries.first { it.kind == kind }
    }
}

/** What the wearer is told when a measurement gave no reading. */
@get:StringRes
val SpotFailure.message: Int
    get() = when (this) {
        SpotFailure.NOT_SUPPORTED -> R.string.measure_error_not_supported
        SpotFailure.PERMISSION_DENIED -> R.string.measure_error_permission
        SpotFailure.OFF_BODY -> R.string.measure_error_off_body
        SpotFailure.NO_SIGNAL -> R.string.measure_error_no_signal
        SpotFailure.FAILED -> R.string.measure_error_failed
    }

/** A measured value as the screen shows it: whole units. */
fun formatMeasurement(value: Double): String = formatCount(value.roundToInt())

/** The altitude that goes with a pressure reading, in the unit system's length unit, with that unit's string. */
fun formatAltitude(metres: Double, unitSystem: UnitSystem): Pair<String, Int> = when (unitSystem) {
    UnitSystem.METRIC -> formatCount(metres.roundToInt()) to R.string.unit_m
    UnitSystem.IMPERIAL -> formatCount((metres * FeetPerMeter).roundToInt()) to R.string.unit_ft
}
