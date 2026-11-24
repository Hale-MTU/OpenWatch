package tech.mmarca.openwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotRulesTest {

    /** Beat times in milliseconds from [intervals] between them, the first beat at zero. */
    private fun beats(intervals: List<Double>): List<Double> = intervals.runningFold(0.0) { at, interval -> at + interval }

    @Test
    fun `a heart rate settles only once enough readings are a heart's`() {
        assertNull(SpotRules.settledHeartRate(List(SpotRules.HEART_RATE_READINGS - 1) { 70 }))
        // A reading outside what a heart beats at does not count.
        assertNull(SpotRules.settledHeartRate(List(SpotRules.HEART_RATE_READINGS - 1) { 70 } + 0))
        assertEquals(70.0, SpotRules.settledHeartRate(List(SpotRules.HEART_RATE_READINGS) { 70 })!!, 0.0)
    }

    @Test
    fun `the settled pulse is the median of the newest five, so one stray reading does not move it`() {
        assertEquals(72.0, SpotRules.settledHeartRate(listOf(90, 88, 85, 72, 71, 140, 70, 72))!!, 0.0)
    }

    @Test
    fun `the RMSSD of alternating intervals is their difference`() {
        val intervals = List(SpotRules.HRV_MIN_CLEAN_BEATS + 1) { if (it % 2 == 0) 800.0 else 840.0 }

        assertEquals(40.0, SpotRules.rmssd(beats(intervals))!!, 1e-9)
    }

    @Test
    fun `an interval no heart beats at and an ectopic beat are left out`() {
        val steady = List(SpotRules.HRV_MIN_CLEAN_BEATS + 1) { if (it % 2 == 0) 800.0 else 840.0 }
        val noisy = steady.take(10) + 250.0 + 1_400.0 + steady.drop(10)

        assertEquals(40.0, SpotRules.rmssd(beats(noisy))!!, 1e-9)
    }

    @Test
    fun `too few clean beats give no HRV`() {
        assertNull(SpotRules.rmssd(beats(List(SpotRules.HRV_MIN_CLEAN_BEATS - 2) { 800.0 })))
        assertNull(SpotRules.rmssd(emptyList()))
    }

    @Test
    fun `two beats stand for a pulse only at an interval a heart beats at`() {
        assertEquals(75.0, SpotRules.beatBpm(1_000.0, 1_800.0)!!, 1e-9)
        assertNull(SpotRules.beatBpm(1_000.0, 1_100.0))
        assertNull(SpotRules.beatBpm(1_000.0, 4_000.0))
    }

    @Test
    fun `the air pressure is the mean of five readings, with its altitude at standard pressure`() {
        assertNull(SpotRules.airPressure(List(SpotRules.PRESSURE_READINGS - 1) { 1013.25f }))

        val sea = SpotRules.airPressure(List(SpotRules.PRESSURE_READINGS) { 1013.25f })!!
        assertEquals(1013.25, sea.hectopascals, 1e-3)
        assertEquals(0.0, sea.altitudeMetres, 0.01)

        val hill = SpotRules.airPressure(listOf(1000f, 1001f, 1002f, 1003f, 1004f))!!
        assertEquals(1002.0, hill.hectopascals, 1e-9)
        assertEquals(AscentMinuteCounter.altitudeMetres(1002.0), hill.altitudeMetres, 1e-9)
    }

    @Test
    fun `each measurement has its time limit`() {
        assertEquals(30, SpotKind.HEART_RATE.seconds)
        assertEquals(60, SpotKind.HRV.seconds)
        assertEquals(10, SpotKind.AIR_PRESSURE.seconds)
    }
}
