package tech.mmarca.openwatch.wear

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tech.mmarca.healthcalculations.insights.HeartRateZones
import tech.mmarca.openwatch.wearlink.DaySummary
import tech.mmarca.openwatch.wearlink.WearLinkEntry

/**
 * What the watch itself recorded, as the dashboard and the detail screens
 * read it: today by the hour and the last seven days by the day, each day
 * with the night that ended on it, and the entries made on the watch.
 *
 * A day that is over is read from the store once and kept. Only today is
 * read again, so a refresh costs one day of rows, not the week's. The
 * phone's figures for the days ([PhoneDays]) fill the tiles the watch
 * cannot, and the night that ended today gives the sleep's stages. A
 * heart-rate day is the largest, up to some 8 600 rows. The entries are few,
 * the month the store keeps of what a wearer logs by hand, and are read whole
 * each time, so the newest weight counts however old it is.
 */
internal class WearReadingsSource(
    private val store: MetricStore,
    private val clock: Clock = Clock.systemDefaultZone(),
    /** The water tile's goal, millilitres. */
    private val waterGoalMl: Double = DisplayPreferences.DEFAULT_WATER_GOAL_ML.toDouble(),
) {
    private val pastDays = HashMap<LocalDate, DayReadings>()

    /** The tiles' values, and the stages of the night that ended today as the phone sent them. */
    data class Readings(
        val metrics: Map<MetricTile, MetricUiState> = emptyMap(),
        val sleepStages: Map<SleepStage, Double> = emptyMap(),
    )

    /**
     * The tiles' values now, read again every [REFRESH_MILLIS] while someone
     * collects. Today's time in the heart rate zones is counted on the basis
     * [zoneBasis] gives, here with the read, off the main thread; null counts
     * none. A basis that changes (the phone sent a maximum or a resting rate)
     * is read on at once, not at the next refresh, and so is an entry made on
     * the watch ([MetricStore.updates]) and a day the phone put
     * ([phoneDays]). [zoneBasis] and [phoneDays] are collected on the IO
     * dispatcher too, so they may load a file when they start.
     */
    fun metrics(
        zoneBasis: Flow<HeartRateZones.Basis?>,
        phoneDays: Flow<Map<Long, DaySummary>> = flowOf(emptyMap()),
    ): Flow<Readings> = channelFlow {
        var reading: Job? = null
        combine(zoneBasis.distinctUntilChanged(), phoneDays, store.updates(WearMetrics.ENTRIES)) { basis, phone, _ -> basis to phone }
            .collect { (basis, phone) ->
                // One reader at a time: the days that are kept are not a shared map.
                reading?.cancelAndJoin()
                reading = launch {
                    while (true) {
                        send(Readings(load(basis, phone), WearReadings.sleepStages(phone, LocalDate.now(clock))))
                        delay(REFRESH_MILLIS)
                    }
                }
            }
    }.flowOn(Dispatchers.IO)

    internal fun load(zoneBasis: HeartRateZones.Basis? = null, phone: Map<Long, DaySummary> = emptyMap()): Map<MetricTile, MetricUiState> {
        val zone = clock.zone
        val today = LocalDate.now(clock)
        val days = (DAYS - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            // Only today's zones are shown, so a day that is kept is read without them.
            if (back == 0) read(date, zone, zoneBasis) else pastDays.getOrPut(date) { read(date, zone) }
        }
        pastDays.keys.retainAll { it >= today.minusDays(DAYS.toLong()) }
        return WearReadings.metrics(days, clock.instant(), zone, readEntries(), waterGoalMl, phone)
    }

    /** The entries made on the watch, oldest first, read again each time one is made. */
    fun entries(): Flow<List<WearLinkEntry>> =
        store.updates(WearMetrics.ENTRIES).map { readEntries() }.flowOn(Dispatchers.IO)

    /** Every entry the store keeps: the series' retention bounds them. */
    private fun readEntries(): List<WearLinkEntry> = store.since(WearMetrics.ENTRIES, -1L, ENTRY_LIMIT)

    private fun read(date: LocalDate, zone: ZoneId, zoneBasis: HeartRateZones.Basis? = null): DayReadings {
        // A row's time is its minute or sample start; a day is [midnight, next midnight).
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val until = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return WearReadings.day(
            date = date,
            zone = zone,
            heartRate = store.since(WearMetrics.HEART_RATE, from, ROW_LIMIT, until),
            steps = store.since(WearMetrics.STEPS, from, ROW_LIMIT, until),
            ascent = store.since(WearMetrics.ASCENT, from, ROW_LIMIT, until),
            night = night(WearReadings.nightWindow(date, zone)),
            zoneBasis = zoneBasis,
        )
    }

    /** The night's vitals are read by night, noon to noon, not by the day: a night is not cut in two at midnight. */
    private fun night(window: NightWindow): NightRows {
        val from = window.startMillis - 1
        val until = window.endMillis
        return NightRows(
            hrv = store.since(WearMetrics.HRV, from, ROW_LIMIT, until),
            spo2 = store.since(WearMetrics.SPO2, from, ROW_LIMIT, until),
            skin = store.since(WearMetrics.SKIN_TEMPERATURE, from, ROW_LIMIT, until),
            respiratory = store.since(WearMetrics.RESPIRATORY_RATE, from, ROW_LIMIT, until),
        )
    }

    private companion object {
        const val DAYS = 7
        const val REFRESH_MILLIS = 30_000L

        /** Above a day's most: a heart rate sample every ten seconds is 8 640. */
        const val ROW_LIMIT = 10_000

        /** Above a month of entries at any pace a wearer logs by hand. */
        const val ENTRY_LIMIT = 10_000
    }
}
