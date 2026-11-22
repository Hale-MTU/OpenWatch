package tech.mmarca.openwatch.devices.wearos

import java.time.Instant
import org.junit.Assert.assertEquals
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

        store.clear("w1")

        assertEquals(Instant.EPOCH, store.stepsCursor("w1"))
        assertTrue(dao.rows.isEmpty())
    }
}
