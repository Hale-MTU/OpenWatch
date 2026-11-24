package tech.mmarca.openwatch.wearlink

/**
 * What the phone knows of one local day and the watch shows on its own
 * screens, since it cannot work them out itself: the night that ended on the
 * day (asleep, and the minutes of each stage), the resting heart rate, and
 * the active energy and distance Health Connect holds for the day, from any
 * app. Null is "not known". [epochDay] is the local date's day number.
 */
data class DaySummary(
    val epochDay: Long,
    val sleepMinutes: Int? = null,
    val awakeMinutes: Int? = null,
    val remMinutes: Int? = null,
    val lightMinutes: Int? = null,
    val deepMinutes: Int? = null,
    val restingBpm: Int? = null,
    val activeKcal: Int? = null,
    val distanceMetres: Int? = null,
)

/**
 * The phone's figures for one day, pushed to the watch after a sync:
 *
 * `PUT_DAY <epochDay> <sleep> <awake> <rem> <light> <deep> <restingBpm> <activeKcal> <distanceMetres>`
 * → `DAY_OK`, or `ERROR bad_request` for a line the watch does not take.
 *
 * Each value is a whole number or `-` for none: minutes of the night (each at
 * most a day's), beats per minute, kilocalories and metres. Only a watch that
 * announces [WearLinkProtocol.CAP_DAY_SUMMARY] is asked. Added without a
 * version bump: a watch that does not know `dy` is never sent it. A reader
 * takes the ten fields it knows and ignores any after them, so the line may
 * grow at its end.
 */
object WearLinkDaily {

    const val PUT_DAY: String = "PUT_DAY"
    const val DAY_OK: String = "DAY_OK"

    /** The most minutes a night's value may hold: a day's. */
    const val MAX_MINUTES: Int = 24 * 60

    /** The most active energy a day may hold, kilocalories. */
    const val MAX_KCAL: Int = 20_000

    /** The most distance a day may hold, metres. */
    const val MAX_DISTANCE_METRES: Int = 500_000

    private const val NO_VALUE = "-"
    private const val FIELDS = 10

    /** The most a value may be, in the line's order after the day: five night stages, the resting rate, energy, distance. */
    private val LIMITS = List(5) { MAX_MINUTES } + listOf(WearLinkProtocol.MAX_BPM, MAX_KCAL, MAX_DISTANCE_METRES)

    fun format(day: DaySummary): String = listOf(
        PUT_DAY,
        day.epochDay.toString(),
        field(day.sleepMinutes), field(day.awakeMinutes), field(day.remMinutes), field(day.lightMinutes), field(day.deepMinutes),
        field(day.restingBpm), field(day.activeKcal), field(day.distanceMetres),
    ).joinToString(" ")

    /** Null unless [line] is a well-formed `PUT_DAY`: a day number, and every value missing or within what a day can hold. */
    fun parse(line: String): DaySummary? {
        val fields = line.trim().split(' ').filter { it.isNotEmpty() }
        if (fields.size < FIELDS || fields[0] != PUT_DAY) return null
        val epochDay = fields[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val values = fields.subList(2, FIELDS).mapIndexed { index, text ->
            if (text == NO_VALUE) null else text.toIntOrNull()?.takeIf { it in 0..LIMITS[index] } ?: return null
        }
        return DaySummary(epochDay, values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7])
    }

    private fun field(value: Int?): String = value?.toString() ?: NO_VALUE
}
