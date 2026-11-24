package tech.mmarca.openwatch.devices.wearos

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.data.repository.contract.FakeDeviceStateRepository
import tech.mmarca.openwatch.devices.FakeSharedPreferences
import tech.mmarca.openwatch.devices.wearos.WearOsSkinTemperatureImport.NightMedian

class WearOsSyncCursorStoreTest {

    private val state = FakeDeviceStateRepository()
    private val store = WearOsSyncCursorStore(state)

    @Test
    fun `the old file's cursors are taken over once, for the watches still registered`() {
        val legacy = FakeSharedPreferences().apply {
            edit()
                .putLong("hr_cursor_w1", 1_000L)
                .putLong("hrv_cursor_w1", 2_000L)
                .putString("skin_medians_w1", "33.1,33.4")
                .putLong("hr_cursor_gone", 9_000L)
                .apply()
        }

        store.adoptLegacy(legacy, registered = setOf("w1"))

        assertEquals(Instant.ofEpochMilli(1_000L), store.heartRateCursor("w1"))
        assertEquals(Instant.ofEpochMilli(2_000L), store.hrvCursor("w1"))
        // Medians from before nights had dates read with no night.
        assertEquals(listOf(NightMedian(null, 33.1), NightMedian(null, 33.4)), store.skinNightMedians("w1"))
        // A watch removed before the move has nothing to keep.
        assertEquals(Instant.EPOCH, store.heartRateCursor("gone"))
        assertTrue(state.values.keys.none { it == "gone" })

        // Once: a cursor moved on since is not put back.
        store.setHeartRateCursor("w1", Instant.ofEpochMilli(5_000L))
        store.adoptLegacy(legacy, registered = setOf("w1"))
        assertEquals(Instant.ofEpochMilli(5_000L), store.heartRateCursor("w1"))
    }

    @Test
    fun `skin night medians read in every form they were stored in and keep their night and minutes`() {
        // A bare median (0.4.x), a night without its minutes (the first build that kept nights), and today's.
        state.put("w1", "skin_medians", "33.1,34.25@2026-11-07,34.5@2026-11-08/412")
        val medians = listOf(
            NightMedian(null, 33.1),
            NightMedian(LocalDate.parse("2026-11-07"), 34.25, minutes = 0),
            NightMedian(LocalDate.parse("2026-11-08"), 34.5, minutes = 412),
        )
        assertEquals(medians, store.skinNightMedians("w1"))

        store.setSkinNightMedians("w1", medians)

        // The persisted shape: a night is written with its minutes, 0 where none were kept; a bare median stays bare.
        assertEquals("33.1,34.25@2026-11-07/0,34.5@2026-11-08/412", state.values.getValue("w1").getValue("skin_medians"))
        assertEquals(medians, store.skinNightMedians("w1"))
    }

    @Test
    fun `a skin night that does not read keeps its median, and one without a median is dropped`() {
        state.put("w1", "skin_medians", "34.0@tomorrow/60,x@2026-11-07/60,34.5@2026-11-08/many,34.6@2026-11-09/-5")

        assertEquals(
            listOf(
                NightMedian(null, 34.0),
                NightMedian(LocalDate.parse("2026-11-08"), 34.5, minutes = 0),
                NightMedian(LocalDate.parse("2026-11-09"), 34.6, minutes = 0),
            ),
            store.skinNightMedians("w1"),
        )
    }

    @Test
    fun `clearing a watch forgets every cursor it had`() {
        store.setStepsCursor("w1", Instant.ofEpochMilli(1_000L))
        store.setSkinNightMedians("w1", listOf(NightMedian(null, 33.0)))
        store.setSeriesCursor("w1", "hv", "0123456789abcdef.12")

        store.clear("w1")

        assertEquals(Instant.EPOCH, store.stepsCursor("w1"))
        assertNull(store.seriesCursor("w1", "hv"))
        assertTrue(state.values.isEmpty())
    }

    @Test
    fun `a series cursor is kept as the watch gave it, per watch and series`() {
        assertNull(store.seriesCursor("w1", "hr"))

        store.setSeriesCursor("w1", "hr", "0123456789abcdef.12")
        store.setSeriesCursor("w2", "hr", "fedcba9876543210.3")

        assertEquals("0123456789abcdef.12", store.seriesCursor("w1", "hr"))
        assertNull(store.seriesCursor("w1", "hv"))
        // The persisted shape: one device_state row per series, its key the series' own.
        assertEquals("0123456789abcdef.12", state.values.getValue("w1").getValue("series_cursor_hr"))
    }
}
