package tech.mmarca.openwatch.wear.features.metric

import java.time.Duration
import tech.mmarca.healthcalculations.insights.HeartRateZones
import tech.mmarca.openwatch.wear.R

/**
 * How the screens draw the heart rate zones. The zones themselves are
 * health-calculations' [HeartRateZones], on the basis the phone sent
 * (`WearSettings.zoneBasis`), so a heart rate lands in the same zone on the
 * watch and on the phone.
 */
object HeartZoneDisplay {
    /** The five zones' names, very light to near-maximal. */
    val labels = listOf(
        R.string.zone_very_light,
        R.string.zone_light,
        R.string.zone_moderate,
        R.string.zone_hard,
        R.string.zone_maximum,
    )

    /** The zone index, 0 to 4, for [bpm]; null below zone 1. */
    fun zoneOf(bpm: Int, basis: HeartRateZones.Basis): Int? = HeartRateZones.zoneOf(bpm.toLong(), basis.boundsBpm)

    /**
     * Where [bpm] sits on an arc of five equal segments, one per zone: 0 at
     * the start of zone 1, 1 at the maximum, and inside a zone's segment as
     * far as it is through that zone's bpm range.
     */
    fun arcProgress(bpm: Int, basis: HeartRateZones.Basis): Float {
        val bounds = basis.boundsBpm
        val zone = HeartRateZones.zoneOf(bpm.toLong(), bounds) ?: return 0f
        val lower = bounds[zone]
        val upper = bounds.getOrElse(zone + 1) { basis.maxBpm }
        val within = if (upper > lower) ((bpm - lower).toFloat() / (upper - lower)).coerceIn(0f, 1f) else 1f
        return (zone + within) / HeartRateZones.COUNT
    }

    /**
     * Each zone's share of [zoneTime], the time spent in a zone, zone 1 first:
     * the five add up to one, and time below zone 1 is in neither a share nor
     * the total. Empty when no time was spent in any zone.
     */
    fun timeShares(zoneTime: List<Duration>): List<Float> {
        val total = zoneTime.sumOf { it.toMillis() }
        return if (total <= 0L) emptyList() else zoneTime.map { it.toMillis().toFloat() / total }
    }
}
