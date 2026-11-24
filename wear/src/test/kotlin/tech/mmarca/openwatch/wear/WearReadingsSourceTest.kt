package tech.mmarca.openwatch.wear

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.mmarca.healthcalculations.insights.HeartRateZones
import tech.mmarca.openwatch.wearlink.WearLinkEntry
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.AscentMinute
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.HeartRateSample
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.HrvWindow
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.RespiratoryWindow
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.SkinMinute
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.Spo2Measurement
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.StepMinute

/** The watch's own rows as the dashboard and the detail screens read them, from a real store. */
@RunWith(AndroidJUnit4::class)
class WearReadingsSourceTest {

    private val store = MetricStore(ApplicationProvider.getApplicationContext(), name = null)

    /** 2026-11-02, 10:30 UTC. */
    private val now = Instant.parse("2026-11-02T10:30:00Z")
    private val source = WearReadingsSource(store, Clock.fixed(now, ZoneOffset.UTC))

    @After
    fun close() = store.close()

    private fun at(time: String): Long = Instant.parse("2026-11-02T${time}Z").toEpochMilli()

    private fun utc(instant: String): Long = Instant.parse(instant).toEpochMilli()

    /** A source over the same store, for a wearer in Berlin at [instant]. */
    private fun inBerlin(instant: String) = WearReadingsSource(store, Clock.fixed(Instant.parse(instant), BERLIN))

    @Test
    fun `the zones are counted on the basis given, and none without one`() = runBlocking {
        // A minute at 150 bpm: zone 4 of a 200 bpm maximum over a resting 60.
        store.putAll(WearMetrics.HEART_RATE, listOf(HeartRateSample(at("09:00:00"), 150), HeartRateSample(at("09:01:00"), 150)))

        val without = withTimeout(TIMEOUT_MILLIS) { source.metrics(flowOf(null)).first().metrics }
        val with = withTimeout(TIMEOUT_MILLIS) { source.metrics(flowOf(HeartRateZones.Basis(maxBpm = 200, restingBpm = 60))).first().metrics }

        assertTrue(without.getValue(MetricTile.HEART_RATE).zoneTime.isEmpty())
        assertEquals(Duration.ofMinutes(1), with.getValue(MetricTile.HEART_RATE).zoneTime[3])
    }

    @Test
    fun `a basis the phone changes is read on at once, not at the next refresh`() = runBlocking {
        store.putAll(WearMetrics.HEART_RATE, listOf(HeartRateSample(at("09:00:00"), 150), HeartRateSample(at("09:01:00"), 150)))
        val basis = MutableStateFlow(HeartRateZones.Basis(maxBpm = 200, restingBpm = 60))

        // The refresh is half a minute away: a second value inside the timeout can only come from the new basis.
        val zones = mutableListOf<List<Duration>>()
        withTimeout(TIMEOUT_MILLIS) {
            source.metrics(basis).map { it.metrics.getValue(MetricTile.HEART_RATE).zoneTime }.take(2).collect {
                zones += it
                basis.value = HeartRateZones.Basis(maxBpm = 150)
            }
        }

        assertEquals(Duration.ofMinutes(1), zones[0][3]) // zone 4 of 200 over 60
        assertEquals(Duration.ofMinutes(1), zones[1][4]) // zone 5 of a maximum of 150
    }

    @Test
    fun `an entry made on the watch is read on at once, not at the next refresh`() = runBlocking {
        val entries = EntryLog(store, now = { at("09:00:00") })

        // The refresh is half a minute away: a second value inside the timeout can only come from the entry.
        val water = mutableListOf<Double?>()
        withTimeout(TIMEOUT_MILLIS) {
            source.metrics(flowOf(null)).map { it.metrics[MetricTile.HYDRATION]?.current }.take(2).collect {
                water += it
                entries.log(WearLinkEntry.Type.WATER, 250.0)
            }
        }

        assertEquals(listOf(null, 250.0), water)
    }

    @Test
    fun `the entries come oldest first, read again as each is made`() = runBlocking {
        val entries = EntryLog(store, now = { at("09:00:00") })
        entries.log(WearLinkEntry.Type.WEIGHT, 72.4, atMillis = at("08:00:00"))

        val seen = mutableListOf<List<WearLinkEntry.Type>>()
        withTimeout(TIMEOUT_MILLIS) {
            source.entries().map { list -> list.map { it.type } }.take(2).collect {
                seen += it
                entries.log(WearLinkEntry.Type.WATER, 250.0)
            }
        }

        assertEquals(listOf(listOf(WearLinkEntry.Type.WEIGHT), listOf(WearLinkEntry.Type.WEIGHT, WearLinkEntry.Type.WATER)), seen)
    }

    @Test
    fun `a night either side of midnight is one night, on the day it ends`() {
        // Asleep from 23:00 to 07:00: readings on both calendar days, and one from the night before.
        store.putAll(
            WearMetrics.HRV,
            listOf(
                HrvWindow(Instant.parse("2026-11-01T02:00:00Z").toEpochMilli(), 300, 80),
                HrvWindow(Instant.parse("2026-11-01T23:30:00Z").toEpochMilli(), 400, 80),
                HrvWindow(at("05:00:00"), 600, 80),
            ),
        )

        val hrv = source.load().getValue(MetricTile.HRV)

        assertEquals(50.0, hrv.current!!, 1e-9)
        assertEquals(listOf(40.0, 60.0), hrv.today)
        assertEquals(listOf(30.0, 50.0), hrv.week)
    }

    @Test
    fun `a reading after noon belongs to the night that ends tomorrow`() {
        store.putAll(
            WearMetrics.SPO2,
            listOf(
                Spo2Measurement(Instant.parse("2026-11-01T12:00:00Z").toEpochMilli(), 96, null), // noon is the night's first minute
                Spo2Measurement(at("11:59:00"), 98, null),
                Spo2Measurement(at("12:00:00"), 90, null),
            ),
        )

        assertEquals(97.0, source.load().getValue(MetricTile.BLOOD_OXYGEN).current!!, 1e-9)
    }

    @Test
    fun `each series comes out in its tile's unit`() {
        store.putAll(WearMetrics.STEPS, listOf(StepMinute(at("08:00:00"), 60), StepMinute(at("09:09:00"), 20), StepMinute(at("09:10:00"), 20)))
        // 60 decimetres climbed in a minute with 40 steps around it: 6 m, two floors of 3 m.
        store.put(WearMetrics.ASCENT, AscentMinute(at("09:10:00"), 60))
        store.putAll(WearMetrics.HEART_RATE, listOf(HeartRateSample(at("09:00:00"), 60), HeartRateSample(at("09:00:10"), 80)))
        store.put(WearMetrics.HRV, HrvWindow(at("03:00:00"), 412, 80)) // tenths of a millisecond
        store.put(WearMetrics.SPO2, Spo2Measurement(at("03:00:00"), 97, 55))
        store.put(WearMetrics.SKIN_TEMPERATURE, SkinMinute(at("03:00:00"), 3512, 2400)) // hundredths of a degree
        store.put(WearMetrics.RESPIRATORY_RATE, RespiratoryWindow(at("03:00:00"), 142, 2)) // tenths of a breath

        val metrics = source.load()

        val steps = metrics.getValue(MetricTile.STEPS)
        assertEquals(100.0, steps.current!!, 0.0)
        assertEquals(11, steps.today.size) // midnight to the hour of 10:30
        assertEquals(60.0, steps.today[8], 0.0)
        assertEquals(40.0, steps.today[9], 0.0)
        assertEquals(6.0, metrics.getValue(MetricTile.ELEVATION).current!!, 1e-9)
        assertEquals(2.0, metrics.getValue(MetricTile.FLOORS).current!!, 1e-9)
        val heartRate = metrics.getValue(MetricTile.HEART_RATE)
        assertEquals(80.0, heartRate.current!!, 0.0)
        assertEquals(SampleStats(low = 60.0, average = 70.0, high = 80.0), heartRate.todayStats)
        assertEquals(41.2, metrics.getValue(MetricTile.HRV).current!!, 1e-9)
        assertEquals(97.0, metrics.getValue(MetricTile.BLOOD_OXYGEN).current!!, 1e-9)
        assertEquals(35.12, metrics.getValue(MetricTile.SKIN_TEMPERATURE).current!!, 1e-9)
        assertEquals(14.2, metrics.getValue(MetricTile.RESPIRATORY_RATE).current!!, 1e-9)
        assertEquals(metrics.keys.size, metrics.values.count { it.weekEnd == now.atZone(ZoneOffset.UTC).toLocalDate() })
    }

    @Test
    fun `a day is from midnight to midnight, each end's minute on its own day`() {
        store.putAll(
            WearMetrics.STEPS,
            listOf(
                StepMinute(utc("2026-11-01T23:59:00Z"), 150), // yesterday's last minute
                StepMinute(at("00:00:00"), 10), // today's first
                StepMinute(at("23:59:00"), 20), // today's last
                StepMinute(utc("2026-11-03T00:00:00Z"), 170), // tomorrow's first
            ),
        )

        val steps = source.load().getValue(MetricTile.STEPS)

        assertEquals(30.0, steps.current!!, 0.0)
        assertEquals(10.0, steps.today[0], 0.0)
        assertEquals(listOf(150.0, 30.0), steps.week)
    }

    @Test
    fun `the day the clocks go forward is 23 hours, and its night ends at the new noon`() {
        // Berlin, 29 March 2026: midnight is 23:00 UTC the day before, the next one 22:00 UTC; 02:00 to 03:00 does not exist.
        store.putAll(
            WearMetrics.STEPS,
            listOf(
                StepMinute(utc("2026-03-28T22:59:00Z"), 1), // 23:59 on the 28th
                StepMinute(utc("2026-03-28T23:00:00Z"), 2), // 00:00
                StepMinute(utc("2026-03-29T01:30:00Z"), 16), // 03:30 summer time
                StepMinute(utc("2026-03-29T21:59:00Z"), 4), // 23:59
                StepMinute(utc("2026-03-29T22:00:00Z"), 8), // 00:00 on the 30th
            ),
        )
        // The night of the 29th: noon winter time (11:00 UTC) to noon summer time (10:00 UTC).
        store.putAll(
            WearMetrics.SPO2,
            listOf(
                Spo2Measurement(utc("2026-03-28T10:59:00Z"), 90, null), // 11:59: still the night that ended on the 28th
                Spo2Measurement(utc("2026-03-28T11:00:00Z"), 96, null),
                Spo2Measurement(utc("2026-03-29T09:59:00Z"), 98, null), // 11:59 summer time
                Spo2Measurement(utc("2026-03-29T10:00:00Z"), 80, null), // noon: the night that ends on the 30th
            ),
        )

        val metrics = inBerlin("2026-03-29T12:00:00Z").load()

        val steps = metrics.getValue(MetricTile.STEPS)
        assertEquals(22.0, steps.current!!, 0.0)
        assertEquals(listOf(1.0, 22.0), steps.week)
        assertEquals(2.0, steps.today[0], 0.0)
        assertEquals(0.0, steps.today[2], 0.0)
        assertEquals(16.0, steps.today[3], 0.0)
        val spo2 = metrics.getValue(MetricTile.BLOOD_OXYGEN)
        assertEquals(97.0, spo2.current!!, 1e-9)
        assertEquals(listOf(90.0, 97.0), spo2.week)
    }

    @Test
    fun `the day the clocks go back is 25 hours, with the hour that comes twice counted once`() {
        // Berlin, 25 October 2026: midnight is 22:00 UTC the day before, the next one 23:00 UTC; 02:00 to 03:00 comes twice.
        store.putAll(
            WearMetrics.STEPS,
            listOf(
                StepMinute(utc("2026-10-24T21:59:00Z"), 3), // 23:59 on the 24th
                StepMinute(utc("2026-10-24T22:00:00Z"), 1), // 00:00
                StepMinute(utc("2026-10-25T00:30:00Z"), 10), // 02:30 summer time
                StepMinute(utc("2026-10-25T01:30:00Z"), 20), // 02:30 winter time
                StepMinute(utc("2026-10-25T22:30:00Z"), 5), // 23:30, the 25th hour
                StepMinute(utc("2026-10-25T23:00:00Z"), 7), // 00:00 on the 26th
            ),
        )

        val steps = inBerlin("2026-10-25T12:00:00Z").load().getValue(MetricTile.STEPS)

        assertEquals(36.0, steps.current!!, 0.0)
        assertEquals(listOf(3.0, 36.0), steps.week)
        assertEquals(30.0, steps.today[2], 0.0)
    }

    @Test
    fun `the longest day of heart rate the recorder can write is read whole`() {
        // A sample every ten seconds over the 25-hour day: 9 000 rows, the lowest first and the highest last.
        val midnight = utc("2026-10-24T22:00:00Z")
        val count = TileDay.MAX_HEART_RATE_SAMPLES_PER_DAY
        store.putAll(
            WearMetrics.HEART_RATE,
            (0 until count).map { index ->
                HeartRateSample(midnight + index * 10_000L, bpm = when (index) { 0 -> 40; count - 1 -> 180; else -> 70 })
            },
        )

        val heartRate = inBerlin("2026-10-25T22:59:59Z").load().getValue(MetricTile.HEART_RATE)

        assertEquals(180.0, heartRate.current!!, 0.0)
        assertEquals(40.0, heartRate.todayStats!!.low, 0.0)
        assertEquals(180.0, heartRate.todayStats!!.high, 0.0)
        assertEquals((40 + 180 + 70.0 * (count - 2)) / count, heartRate.todayStats!!.average!!, 1e-9)
    }

    @Test
    fun `the skin temperature's chart is the mean of each quarter hour, its value the median of the minutes`() {
        store.putAll(
            WearMetrics.SKIN_TEMPERATURE,
            listOf(
                SkinMinute(at("02:00:00"), 3500, 2400),
                SkinMinute(at("02:01:00"), 3520, 2400),
                SkinMinute(at("02:15:00"), 3560, 2400),
            ),
        )

        val skin = source.load().getValue(MetricTile.SKIN_TEMPERATURE)

        assertEquals(2, skin.today.size)
        assertEquals(35.1, skin.today[0], 1e-9)
        assertEquals(35.6, skin.today[1], 1e-9)
        assertEquals(35.2, skin.current!!, 1e-9)
        assertEquals(SampleStats(low = 35.0, average = null, high = 35.6), skin.todayStats)
    }

    @Test
    fun `a day that is over is read once, and today again at every load`() {
        store.putAll(WearMetrics.STEPS, listOf(StepMinute(utc("2026-11-01T12:00:00Z"), 150), StepMinute(at("08:00:00"), 10)))
        assertEquals(listOf(150.0, 10.0), source.load().getValue(MetricTile.STEPS).week)

        // Rows do not arrive for a day that is over; one written anyway shows that yesterday is not read again.
        store.putAll(WearMetrics.STEPS, listOf(StepMinute(utc("2026-11-01T13:00:00Z"), 100), StepMinute(at("09:00:00"), 5)))

        assertEquals(listOf(150.0, 15.0), source.load().getValue(MetricTile.STEPS).week)
    }

    @Test
    fun `a week older than seven days is not read, and a store without rows gives no tile`() {
        // A source keeps the days that are over, so each look at the store is a new one's.
        fun load() = WearReadingsSource(store, Clock.fixed(now, ZoneOffset.UTC)).load()
        assertTrue(load().isEmpty())

        // Seven days back from 2 November is 27 October; the 26th is out.
        store.put(WearMetrics.STEPS, StepMinute(utc("2026-10-26T12:00:00Z"), 190))
        assertFalse(MetricTile.STEPS in load())

        store.put(WearMetrics.STEPS, StepMinute(utc("2026-10-27T12:00:00Z"), 180))
        assertEquals(listOf(180.0, null, null, null, null, null, null), load().getValue(MetricTile.STEPS).week)
    }

    private companion object {
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
        const val TIMEOUT_MILLIS = 10_000L
    }
}
