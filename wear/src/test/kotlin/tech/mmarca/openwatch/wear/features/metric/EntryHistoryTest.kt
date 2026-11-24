package tech.mmarca.openwatch.wear.features.metric

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.mmarca.openwatch.wear.MetricTile
import tech.mmarca.openwatch.wearlink.WearLinkEntry
import tech.mmarca.openwatch.wearlink.WearLinkEntry.Type

class EntryHistoryTest {

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 5)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = date.atTime(hour, minute).toInstant(zone).toEpochMilli()

    @Test
    fun `the newest entries of the metric come first, today without a day`() {
        val entries = listOf(
            WearLinkEntry.of(at(today.minusDays(1), 22), Type.HRV, 55.0, measured = true),
            WearLinkEntry.of(at(today, 1, 36), Type.HRV, 78.0, measured = true),
            WearLinkEntry.of(at(today, 1, 50), Type.HRV, 64.0, measured = true),
            WearLinkEntry.of(at(today, 8), Type.WEIGHT, 81.0),
        )

        val recent = recentEntries(entries, MetricTile.HRV, today, zone)

        assertEquals(listOf(64.0, 78.0, 55.0), recent.map { it.value })
        assertEquals(listOf(null, null, today.minusDays(1)), recent.map { it.day })
    }

    @Test
    fun `at most five are listed, and a tile with no entry type lists none`() {
        val entries = (0 until 8).map { WearLinkEntry.of(at(today, it), Type.HRV, it + 1.0, measured = true) }

        assertEquals(listOf(8.0, 7.0, 6.0, 5.0, 4.0), recentEntries(entries, MetricTile.HRV, today, zone).map { it.value })
        assertEquals(emptyList<RecentEntry>(), recentEntries(entries, MetricTile.STEPS, today, zone))
    }

    @Test
    fun `the heart rate lists no entries, its day's curve is its history, and a logged blood oxygen is listed`() {
        val entries = listOf(
            WearLinkEntry.of(at(today, 9), Type.HEART_RATE, 61.0, measured = true),
            WearLinkEntry.of(at(today, 9, 5), Type.BLOOD_OXYGEN, 97.0),
        )

        assertEquals(emptyList<RecentEntry>(), recentEntries(entries, MetricTile.HEART_RATE, today, zone))
        assertEquals(listOf(97.0), recentEntries(entries, MetricTile.BLOOD_OXYGEN, today, zone).map { it.value })
    }
}
