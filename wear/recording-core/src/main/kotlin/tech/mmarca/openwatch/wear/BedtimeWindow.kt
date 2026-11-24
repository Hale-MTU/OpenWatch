package tech.mmarca.openwatch.wear

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The hours a bedtime read from Do Not Disturb may count in: from
 * [START_HOUR] to [END_HOUR] local, wide enough for an early night and a late
 * morning. On a watch without Samsung's bedtime setting, [BedtimeMonitor]
 * reads Do Not Disturb as bedtime, so a manual one or theater mode counts
 * too, and one left on would otherwise hold the processor and the full heart
 * rate all day. Samsung's own setting is the wearer's schedule and is
 * not bounded. Outside the window, sleep is still caught by [SleepHold].
 * Pure.
 */
object BedtimeWindow {

    const val START_HOUR = 21
    const val END_HOUR = 9

    /** Whether [epochMillis] falls inside the window in [zone]. */
    fun contains(epochMillis: Long, zone: ZoneId): Boolean {
        val hour = Instant.ofEpochMilli(epochMillis).atZone(zone).hour
        return hour >= START_HOUR || hour < END_HOUR
    }

    /** How long from [epochMillis] until the window next opens or closes, in [zone]; at least a second. */
    fun millisToNextEdge(epochMillis: Long, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        val next = (0L..1L).asSequence()
            .flatMap { days -> sequenceOf(END_HOUR, START_HOUR).map { hour -> today.plusDays(days) to hour } }
            .map { (date, hour) -> ZonedDateTime.of(date, LocalTime.of(hour, 0), zone).toInstant().toEpochMilli() }
            .filter { it > epochMillis }
            .min()
        return (next - epochMillis).coerceAtLeast(MIN_DELAY_MILLIS)
    }

    private const val MIN_DELAY_MILLIS = 1_000L
}
