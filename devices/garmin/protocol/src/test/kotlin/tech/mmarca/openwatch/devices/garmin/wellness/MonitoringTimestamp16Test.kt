package tech.mmarca.openwatch.devices.garmin.wellness

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** Monitoring records' `timestamp_16`, resolved against the last full timestamp (from FitWellnessImportTest). */
class MonitoringTimestamp16Test {

    private fun utc(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Instant =
        LocalDateTime.of(year, month, day, hour, minute).atZone(ZoneId.of("UTC")).toInstant()

    @Test
    fun `timestamp16 may resolve before its full timestamp anchor`() {
        val anchor = 0x12_1000L

        assertEquals(0x12_0F00L, resolveMonitoringTimestamp16(anchor, 0x0F00))
    }

    @Test
    fun `timestamp16 rolls forward across the unsigned 16 bit boundary`() {
        val anchor = 0x12_FFF0L

        assertEquals(0x13_0010L, resolveMonitoringTimestamp16(anchor, 0x0010))
    }

    @Test
    fun `timestamp16 chooses the nearest rollover instead of shifting a day`() {
        val anchor = fitTimestamp(utc(2026, 8, 27, 0, 5))
        val fiveMinutesEarlier = fitTimestamp(utc(2026, 8, 27, 0, 0))

        assertEquals(
            fiveMinutesEarlier,
            resolveMonitoringTimestamp16(anchor, fiveMinutesEarlier and 0xFFFF),
        )
    }
}
