package tech.mmarca.openwatch.wear

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.mmarca.openwatch.wearlink.WearLinkEntry
import tech.mmarca.openwatch.wearlink.WearLinkEntry.Type

/** The one writer of the watch's entries, over a real store. */
@RunWith(AndroidJUnit4::class)
class EntryLogTest {

    private val store = MetricStore(ApplicationProvider.getApplicationContext(), name = null)
    private var now = 1_791_616_222_062L
    private val log = EntryLog(store, now = { now })

    @After
    fun close() = store.close()

    private fun stored(): List<WearLinkEntry> = store.since(WearMetrics.ENTRIES, 0, 100)

    @Test
    fun `an entry is stored as the line the phone reads, by hand or measured`() {
        assertTrue(log.log(Type.BLOOD_PRESSURE, 121.0, 79.0))
        assertTrue(log.log(Type.HEART_RATE, 64.0, measured = true, atMillis = now + 5_000))

        assertEquals(
            listOf(
                WearLinkEntry(now, Type.BLOOD_PRESSURE, 121_000, 79_000, measured = false),
                WearLinkEntry(now + 5_000, Type.HEART_RATE, 64_000, null, measured = true),
            ),
            stored(),
        )
    }

    @Test
    fun `two glasses in the same millisecond are both kept, the second a millisecond later`() {
        assertTrue(log.log(Type.WATER, 250.0))
        assertTrue(log.log(Type.WATER, 250.0))
        assertTrue(log.log(Type.WATER, 250.0))

        assertEquals(listOf(now, now + 1, now + 2), stored().map { it.epochMillis })
        assertEquals(750.0, stored().sumOf { it.value }, 0.0)
    }

    @Test
    fun `an entry the line cannot carry is refused and nothing is written`() {
        assertFalse(log.log(Type.WATER, 0.0))
        assertFalse(log.log(Type.BLOOD_PRESSURE, 121.0))
        assertFalse(log.log(Type.WEIGHT, 72.0, atMillis = 0))

        assertTrue(stored().isEmpty())
    }

    @Test
    fun `an entry older than a month is pruned when the next is made`() {
        assertTrue(log.log(Type.WEIGHT, 72.0))
        now += WearMetric.MONTH_MILLIS + 1
        assertTrue(log.log(Type.WEIGHT, 71.5))

        assertEquals(listOf(71.5), stored().map { it.value })
    }

    @Test
    fun `each entry moves the series' count, which the screens read again on`() {
        val updates = store.updates(WearMetrics.ENTRIES)

        log.log(Type.WATER, 250.0)
        log.log(Type.MINDFULNESS, 1.0)

        assertEquals(2L, updates.value)
    }

    @Test
    fun `each entry written is told, so the quick log tile can show it, and a refused one is not`() {
        val told = mutableListOf<Type>()
        val telling = EntryLog(store, now = { now }, onLogged = told::add)

        telling.log(Type.WATER, 250.0)
        telling.log(Type.WATER, 0.0)
        telling.log(Type.WEIGHT, 72.0)

        assertEquals(listOf(Type.WATER, Type.WEIGHT), told)
    }
}
