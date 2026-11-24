package tech.mmarca.openwatch.wear.features.recording

import androidx.annotation.StringRes
import java.util.Locale
import kotlin.math.roundToInt
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.UnitSystem
import tech.mmarca.openwatch.wear.ui.components.MetersPerKilometer
import tech.mmarca.openwatch.wear.ui.components.MetersPerMile

private const val SecondsPerMinute = 60

/** The slowest pace shown, 59:59 a kilometre or mile: beyond it the wearer is waiting, not moving. */
private const val SlowestPaceSeconds = 60 * SecondsPerMinute - 1

private fun metersPerUnit(unitSystem: UnitSystem): Double =
    if (unitSystem == UnitSystem.IMPERIAL) MetersPerMile else MetersPerKilometer

/**
 * A workout's distance in kilometres or miles, two decimals: the live value
 * moves every 10 m, and a 3,240 m run reads 3.24 km. The dashboard's daily
 * distance keeps one.
 */
fun formatWorkoutDistance(meters: Int, unitSystem: UnitSystem): String =
    String.format(Locale.getDefault(), "%.2f", meters / metersPerUnit(unitSystem))

/**
 * Minutes and seconds per kilometre or mile, e.g. "5:42". Null, which reads
 * as a dash, when standing still and when slower than 59:59 a unit: a GPS
 * that drifts at 0.11 m/s would otherwise read "151:31 /km".
 */
fun formatPace(speedMetersPerSecond: Double, unitSystem: UnitSystem): String? {
    if (speedMetersPerSecond <= 0.0) return null
    val seconds = (metersPerUnit(unitSystem) / speedMetersPerSecond).roundToInt()
    if (seconds > SlowestPaceSeconds) return null
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
