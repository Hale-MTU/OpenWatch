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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import tech.mmarca.healthcalculations.insights.HeartRateZones

/**
 * What the watch itself recorded, as the dashboard and the detail screens
 * read it: today by the hour and the last seven days by the day, each day
 * with the night that ended on it.
 *
 * A day that is over is read from the store once and kept. Only today is
 * read again, so a refresh costs one day of rows, not the week's. A
 * heart-rate day is the largest, up to some 8 600 rows.
 */
internal class WearReadingsSource(
    private val store: MetricStore,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val pastDays = HashMap<LocalDate, DayReadings>()

    /**
     * The tiles' values now, read again every [REFRESH_MILLIS] while someone
     * collects. Today's time in the heart rate zones is counted on the basis
     * [zoneBasis] gives, here with the read, off the main thread; null counts
     * none. A basis that changes (the phone sent a maximum or a resting rate)
     * is read on at once, not at the next refresh. [zoneBasis] is collected on
     * the IO dispatcher too, so it may load a file when it starts.
     */
    fun metrics(zoneBasis: Flow<HeartRateZones.Basis?>): Flow<Map<MetricTile, MetricUiState>> = channelFlow {
        var reading: Job? = null
        zoneBasis.distinctUntilChanged().collect { basis ->
            // One reader at a time: the days that are kept are not a shared map.
            reading?.cancelAndJoin()
            reading = launch {
                while (true) {
                    send(load(basis))
                    delay(REFRESH_MILLIS)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    internal fun load(zoneBasis: HeartRateZones.Basis? = null): Map<MetricTile, MetricUiState> {
        val zone = clock.zone
        val today = LocalDate.now(clock)
        val days = (DAYS - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            // Only today's zones are shown, so a day that is kept is read without them.
            if (back == 0) read(date, zone, zoneBasis) else pastDays.getOrPut(date) { read(date, zone) }
        }
        pastDays.keys.retainAll { it >= today.minusDays(DAYS.toLong()) }
        return WearReadings.metrics(days, clock.instant(), zone)
    }

    /** Today only, read now: what the Today tile shows. */
    fun today(): DayReadings = read(LocalDate.now(clock), clock.zone)

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
    }
}
