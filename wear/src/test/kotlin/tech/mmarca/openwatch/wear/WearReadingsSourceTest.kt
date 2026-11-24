package tech.mmarca.openwatch.wear

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Clock
import java.time.Duration
import java.time.Instant
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.mmarca.healthcalculations.insights.HeartRateZones
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.HeartRateSample
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.HrvWindow
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.Spo2Measurement

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

    @Test
    fun `the zones are counted on the basis given, and none without one`() = runBlocking {
        // A minute at 150 bpm: zone 4 of a 200 bpm maximum over a resting 60.
        store.putAll(WearMetrics.HEART_RATE, listOf(HeartRateSample(at("09:00:00"), 150), HeartRateSample(at("09:01:00"), 150)))

        val without = withTimeout(TIMEOUT_MILLIS) { source.metrics(flowOf(null)).first() }
        val with = withTimeout(TIMEOUT_MILLIS) { source.metrics(flowOf(HeartRateZones.Basis(maxBpm = 200, restingBpm = 60))).first() }

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
            source.metrics(basis).map { it.getValue(MetricTile.HEART_RATE).zoneTime }.take(2).collect {
                zones += it
                basis.value = HeartRateZones.Basis(maxBpm = 150)
            }
        }

        assertEquals(Duration.ofMinutes(1), zones[0][3]) // zone 4 of 200 over 60
        assertEquals(Duration.ofMinutes(1), zones[1][4]) // zone 5 of a maximum of 150
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

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
