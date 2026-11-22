package tech.mmarca.openwatch.devices.wearos

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.data.repository.FakeDeviceStateDao
import tech.mmarca.openwatch.data.repository.testDeviceStateStore
import tech.mmarca.openwatch.devices.FakeSharedPreferences

class WearOsSyncCursorStoreTest {

    private val dao = FakeDeviceStateDao()
    private val store = WearOsSyncCursorStore(testDeviceStateStore(dao))

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
        assertEquals(listOf(33.1, 33.4), store.skinNightMedians("w1"))
        // A watch removed before the move has nothing to keep.
        assertEquals(Instant.EPOCH, store.heartRateCursor("gone"))
        assertTrue(dao.rows.keys.none { it.first == "gone" })

        // Once: a cursor moved on since is not put back.
        store.setHeartRateCursor("w1", Instant.ofEpochMilli(5_000L))
        store.adoptLegacy(legacy, registered = setOf("w1"))
        assertEquals(Instant.ofEpochMilli(5_000L), store.heartRateCursor("w1"))
    }

    @Test
    fun `clearing a watch forgets every cursor it had`() {
        store.setStepsCursor("w1", Instant.ofEpochMilli(1_000L))
        store.setSkinNightMedians("w1", listOf(33.0))
        store.setSeriesCursor("w1", "hv", "0123456789abcdef.12")

        store.clear("w1")

        assertEquals(Instant.EPOCH, store.stepsCursor("w1"))
        assertNull(store.seriesCursor("w1", "hv"))
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `a series cursor is kept as the watch gave it, per watch and series`() {
        assertNull(store.seriesCursor("w1", "hr"))

        store.setSeriesCursor("w1", "hr", "0123456789abcdef.12")
        store.setSeriesCursor("w2", "hr", "fedcba9876543210.3")

        assertEquals("0123456789abcdef.12", store.seriesCursor("w1", "hr"))
        assertNull(store.seriesCursor("w1", "hv"))
        // The persisted shape: one device_state row per series, its key the series' own.
        assertEquals("0123456789abcdef.12", dao.rows.getValue("w1" to "series_cursor_hr").value)
    }
}
