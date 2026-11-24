package tech.mmarca.openwatch.wear

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.wearlink.WearLinkEntry
import tech.mmarca.openwatch.wearlink.WearLinkEntry.Type

class EntryDaysTest {

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 2)
    private val yesterday = today.minusDays(1)

    private fun at(day: LocalDate, hour: Int, minute: Int = 0, zone: ZoneId = this.zone) =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun entry(day: LocalDate, hour: Int, type: Type, value: Double, minute: Int = 0) =
        WearLinkEntry.of(at(day, hour, minute), type, value)

    @Test
    fun `water adds up by the hour, and a day without any is no data`() {
        val entries = listOf(
            entry(yesterday, 9, Type.WATER, 500.0),
            entry(today, 8, Type.WATER, 250.0),
            entry(today, 10, Type.WATER, 250.0),
            entry(today, 10, Type.WATER, 250.0, minute = 30),
            entry(today, 11, Type.WEIGHT, 72.4),
        )

        val days = EntryDays.of(entries, listOf(yesterday.minusDays(1), yesterday, today), zone)

        assertFalse(days[0].hasWater)
        assertEquals(0.0, days[0].waterByHour.sum(), 0.0)
        assertTrue(days[1].hasWater)
        assertEquals(500.0, days[1].waterByHour[9], 0.0)
        assertEquals(24, days[2].waterByHour.size)
        assertEquals(250.0, days[2].waterByHour[8], 0.0)
        assertEquals(500.0, days[2].waterByHour[10], 0.0)
        assertEquals(750.0, days[2].waterByHour.sum(), 0.0)
    }

    @Test
    fun `a day's weights come oldest first, and the days come in the order asked`() {
        val entries = listOf(
            entry(today, 20, Type.WEIGHT, 72.9),
            entry(today, 7, Type.WEIGHT, 72.4),
            entry(yesterday, 7, Type.WEIGHT, 72.6),
        )

        val days = EntryDays.of(entries, listOf(yesterday, today), zone)

        assertEquals(listOf(yesterday, today), days.map { it.date })
        assertEquals(listOf(72.6), days[0].weights.map { it.value })
        assertEquals(listOf(72.4, 72.9), days[1].weights.map { it.value })
    }

    @Test
    fun `a day is the local one, midnight to midnight`() {
        val berlin = ZoneId.of("Europe/Berlin")
        // 23:30 UTC on the first is 00:30 on the second in Berlin.
        val late = WearLinkEntry.of(at(yesterday, 23, 30), Type.WATER, 250.0)

        assertTrue(EntryDays.of(listOf(late), listOf(today), berlin).single().hasWater)
        assertFalse(EntryDays.of(listOf(late), listOf(today), zone).single().hasWater)
        assertEquals(250.0, EntryDays.of(listOf(late), listOf(today), berlin).single().waterByHour[0], 0.0)
    }

    @Test
    fun `the newest entry of a type, at all and on one day`() {
        val entries = listOf(
            entry(yesterday, 9, Type.HEART_RATE, 61.0),
            entry(today, 7, Type.HEART_RATE, 64.0),
            entry(today, 6, Type.HEART_RATE, 70.0),
            entry(yesterday, 22, Type.HRV, 41.0),
        )

        assertEquals(64.0, EntryDays.latest(entries, Type.HEART_RATE)!!.value, 0.0)
        assertEquals(64.0, EntryDays.latestOn(entries, Type.HEART_RATE, today, zone)!!.value, 0.0)
        assertEquals(41.0, EntryDays.latest(entries, Type.HRV)!!.value, 0.0)
        assertNull(EntryDays.latestOn(entries, Type.HRV, today, zone))
        assertNull(EntryDays.latest(entries, Type.WEIGHT))
    }
}
