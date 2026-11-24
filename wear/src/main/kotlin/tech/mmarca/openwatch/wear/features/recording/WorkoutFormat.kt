package tech.mmarca.openwatch.wear.features.recording

import androidx.annotation.StringRes
import java.util.Locale
import kotlin.math.roundToInt
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.UnitSystem
import tech.mmarca.openwatch.wear.ui.components.MetersPerKilometer
import tech.mmarca.openwatch.wear.ui.components.MetersPerMile

private const val SecondsPerMinute = 60

private fun metersPerUnit(unitSystem: UnitSystem): Double =
    if (unitSystem == UnitSystem.IMPERIAL) MetersPerMile else MetersPerKilometer

/**
 * A workout's distance in kilometres or miles, two decimals: the live value
 * moves every 10 m, and a 3,240 m run reads 3.24 km. The dashboard's daily
 * distance keeps one.
 */
fun formatWorkoutDistance(meters: Int, unitSystem: UnitSystem): String =
    String.format(Locale.getDefault(), "%.2f", meters / metersPerUnit(unitSystem))

/** Minutes and seconds per kilometre or mile, e.g. "5:42". Null when standing still. */
fun formatPace(speedMetersPerSecond: Double, unitSystem: UnitSystem): String? {
    if (speedMetersPerSecond <= 0.1) return null
    val seconds = (metersPerUnit(unitSystem) / speedMetersPerSecond).roundToInt()
    return String.format(Locale.getDefault(), "%d:%02d", seconds / SecondsPerMinute, seconds % SecondsPerMinute)
}

@StringRes
fun paceUnit(unitSystem: UnitSystem): Int =
    if (unitSystem == UnitSystem.IMPERIAL) R.string.unit_pace_mi else R.string.unit_pace_km

/** Kilometres or miles per hour, one decimal. */
fun formatSpeed(speedMetersPerSecond: Double, unitSystem: UnitSystem): String =
    String.format(Locale.getDefault(), "%.1f", speedMetersPerSecond * 3600 / metersPerUnit(unitSystem))

@StringRes
fun speedUnit(unitSystem: UnitSystem): Int =
    if (unitSystem == UnitSystem.IMPERIAL) R.string.unit_mph else R.string.unit_kmh
