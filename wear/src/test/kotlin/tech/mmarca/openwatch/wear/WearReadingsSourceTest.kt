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

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
