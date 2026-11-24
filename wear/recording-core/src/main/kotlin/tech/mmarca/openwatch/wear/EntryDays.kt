package tech.mmarca.openwatch.wear

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import tech.mmarca.openwatch.wearlink.WearLinkEntry

/** One local day of the entries made on the watch ([WearMetrics.ENTRIES]), as the dashboard counts them. */
data class EntryDay(
    val date: LocalDate,
    /** Millilitres of water logged per hour of the day, 24 values. */
    val waterByHour: List<Double>,
    /** Whether any water was logged on the day: a day without is no data, not a total of zero. */
    val hasWater: Boolean,
    /** The day's weights, oldest first. */
    val weights: List<WearLinkEntry>,
)

/**
 * The entries made on the watch, by local day: the water drunk by the hour,
 * the weights, the newest value of a type. Pure; the screens' state is put
 * together from it in `WearReadings`.
 */
object EntryDays {

    private const val HOURS = 24

    /** One [EntryDay] for each of [dates], in their order, from [entries] in any order. */
    fun of(entries: List<WearLinkEntry>, dates: List<LocalDate>, zone: ZoneId): List<EntryDay> {
        val byDate = entries.groupBy { dateOf(it, zone) }
        return dates.map { date ->
            val day = byDate[date].orEmpty().sortedBy { it.epochMillis }
            val water = day.filter { it.type == WearLinkEntry.Type.WATER }
            val waterByHour = DoubleArray(HOURS)
            for (entry in water) waterByHour[Instant.ofEpochMilli(entry.epochMillis).atZone(zone).hour] += entry.value
            EntryDay(
                date = date,
                waterByHour = waterByHour.toList(),
                hasWater = water.isNotEmpty(),
                weights = day.filter { it.type == WearLinkEntry.Type.WEIGHT },
            )
        }
    }

    /** The newest entry of [type], or null when there is none. */
    fun latest(entries: List<WearLinkEntry>, type: WearLinkEntry.Type): WearLinkEntry? =
        entries.filter { it.type == type }.maxByOrNull { it.epochMillis }

    /** The newest entry of [type] made on [date], or null. */
    fun latestOn(entries: List<WearLinkEntry>, type: WearLinkEntry.Type, date: LocalDate, zone: ZoneId): WearLinkEntry? =
        entries.filter { it.type == type && dateOf(it, zone) == date }.maxByOrNull { it.epochMillis }

    private fun dateOf(entry: WearLinkEntry, zone: ZoneId): LocalDate = Instant.ofEpochMilli(entry.epochMillis).atZone(zone).toLocalDate()
}
