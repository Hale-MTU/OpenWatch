package tech.mmarca.openwatch.wear.features.metric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.healthcalculations.insights.HeartRateZones

class HeartRateZonesTest {

    @Test
    fun `the zone is the shared library's, on the maximum the phone sent`() {
        // 155 bpm is zone 5 with a maximum of 160; on a fixed 190 it would be zone 4.
        val basis = HeartRateZones.Basis(maxBpm = 160)
        assertEquals(4, HeartZoneDisplay.zoneOf(155, basis))
        assertEquals(HeartRateZones.zoneOf(150, basis.boundsBpm), HeartZoneDisplay.zoneOf(150, basis))
    }

    @Test
    fun `with a resting rate a heart rate below it is in no zone`() {
        assertNull(HeartZoneDisplay.zoneOf(50, HeartRateZones.Basis(maxBpm = 180, restingBpm = 60)))
    }

    @Test
    fun `the arc's dot stays inside the lit zone's segment`() {
        val basis = HeartRateZones.Basis(maxBpm = 200)
        val bounds = basis.boundsBpm
        assertEquals(0f, HeartZoneDisplay.arcProgress(bounds[0], basis), 0f)
        assertEquals(0.4f, HeartZoneDisplay.arcProgress(bounds[2], basis), 1e-6f)
        assertEquals(1f, HeartZoneDisplay.arcProgress(200, basis), 0f)
        assertEquals(1f, HeartZoneDisplay.arcProgress(230, basis), 0f)
        for (bpm in 40..200) {
            val zone = HeartZoneDisplay.zoneOf(bpm, basis) ?: continue
            val progress = HeartZoneDisplay.arcProgress(bpm, basis)
            assertTrue("$bpm bpm drawn outside zone ${zone + 1}", progress >= zone / 5f && progress <= (zone + 1) / 5f)
        }
    }

    @Test
    fun `the zones' shares count each sample in its zone and none below zone 1`() {
        val basis = HeartRateZones.Basis(maxBpm = 200, restingBpm = 60)
        val bounds = basis.boundsBpm
        val samples = listOf(40.0, bounds[0].toDouble(), bounds[0].toDouble(), bounds[4].toDouble())
        val shares = HeartZoneDisplay.zoneShares(samples, basis)
        assertEquals(listOf(0.5f, 0f, 0f, 0f, 0.25f), shares)
        assertEquals(List(5) { 0f }, HeartZoneDisplay.zoneShares(emptyList(), basis))
    }
}
