package tech.mmarca.openwatch.wear

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The night window itself; the monitor that bounds a Do Not Disturb bedtime by it is the watch app's `BedtimeMonitorTest`. */
class BedtimeWindowTest {

    private val zone = ZoneId.of("Europe/Madrid")

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `the window runs from nine in the evening to nine in the morning`() {
        assertFalse(BedtimeWindow.contains(at(10, 20, 59), zone))
        assertTrue(BedtimeWindow.contains(at(10, 21), zone))
        assertTrue(BedtimeWindow.contains(at(11, 3), zone))
        assertTrue(BedtimeWindow.contains(at(11, 8, 59), zone))
        assertFalse(BedtimeWindow.contains(at(11, 9), zone))
    }

    @Test
    fun `the next edge is the window's next opening or closing`() {
        assertEquals(Duration.ofHours(1).toMillis(), BedtimeWindow.millisToNextEdge(at(10, 20), zone))
        assertEquals(Duration.ofHours(10).toMillis(), BedtimeWindow.millisToNextEdge(at(10, 23), zone))
        assertEquals(Duration.ofHours(12).toMillis(), BedtimeWindow.millisToNextEdge(at(10, 9), zone))
    }

    @Test
    fun `the night the clocks go back is an hour longer`() {
        // 25 October 2026: Madrid falls back from 03:00 to 02:00.
        val evening = LocalDateTime.of(2026, 10, 24, 21, 0).atZone(zone).toInstant().toEpochMilli()

        assertEquals(Duration.ofHours(13).toMillis(), BedtimeWindow.millisToNextEdge(evening, zone))
    }
}
