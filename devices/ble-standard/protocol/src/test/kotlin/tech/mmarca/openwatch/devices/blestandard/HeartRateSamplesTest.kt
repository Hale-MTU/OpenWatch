package tech.mmarca.openwatch.devices.blestandard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateSamplesTest {

    private val run = HeartRateSamples(
        listOf(HeartRateSample(1_764_000_000_000, 71), HeartRateSample(1_764_000_001_000, 72)),
        zoneOffsetSeconds = 3600,
    )

    @Test
    fun `a run is kept in the stored format, under its first sample's time, and read back as it was`() {
        assertEquals("hr1;3600;1764000000000:71,1764000001000:72", run.encode())
        assertEquals("hr_1764000000000", run.key)
        assertEquals(run, HeartRateSamples.decode(run.encode()))
    }

    @Test
    fun `a payload of another version or damaged reads as nothing`() {
        assertNull(HeartRateSamples.decode("hr2;0;1:60"))
        assertNull(HeartRateSamples.decode("hr1;x;1:60"))
        assertNull(HeartRateSamples.decode("hr1;0;1-60"))
        assertNull(HeartRateSamples.decode("hr1;0;"))
        assertNull(HeartRateSamples.decode(""))
    }

    @Test
    fun `samples gather into one run per clock minute`() {
        val minutes = HeartRateMinutes { 0 }
        val first = 1_764_000_000_000L - 1_764_000_000_000L % 60_000

        assertNull(minutes.add(HeartRateSample(first + 1_000, 60)))
        assertNull(minutes.add(HeartRateSample(first + 59_000, 61)))
        val done = minutes.add(HeartRateSample(first + 60_000, 62))

        assertEquals(listOf(60, 61), done?.samples?.map { it.beatsPerMinute })
        assertEquals(listOf(62), minutes.drain()?.samples?.map { it.beatsPerMinute })
        assertNull(minutes.drain())
    }
}
