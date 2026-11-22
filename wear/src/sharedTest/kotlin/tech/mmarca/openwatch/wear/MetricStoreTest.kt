package tech.mmarca.openwatch.wear

import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

/** The store against real SQLite, on a device; an in-memory database per test. */
@RunWith(AndroidJUnit4::class)
class MetricStoreTest {

    private lateinit var store: MetricStore

    @Before
    fun open() {
        store = MetricStore(ApplicationProvider.getApplicationContext(), name = null)
    }

    @After
    fun close() = store.close()

    @Test
    fun pagesAreOldestFirstNewerThanTheCursorAndLimited() {
        store.putAll(WearMetrics.HEART_RATE, listOf(hr(3_000, 70), hr(1_000, 60), hr(2_000, 65), hr(4_000, 75)))

        assertEquals(listOf(hr(2_000, 65), hr(3_000, 70)), store.since(WearMetrics.HEART_RATE, 1_000, limit = 2))
        assertEquals(hr(4_000, 75), store.latest(WearMetrics.HEART_RATE))
        assertEquals(4L, store.count(WearMetrics.HEART_RATE))
    }

    @Test
    fun aSampleAtTheSameTimeIsKeptAMinuteIsReplaced() {
        store.put(WearMetrics.HEART_RATE, hr(1_000, 60))
        store.put(WearMetrics.HEART_RATE, hr(1_000, 99))
        assertEquals(listOf(hr(1_000, 60)), store.since(WearMetrics.HEART_RATE, 0, 10))

        store.put(WearMetrics.SLEEP_MINUTES, minute(60_000, samples = 30))
        store.put(WearMetrics.SLEEP_MINUTES, minute(60_000, samples = 374))
        assertEquals(listOf(374), store.since(WearMetrics.SLEEP_MINUTES, 0, 10).map { it.sampleCount })
    }

    @Test
    fun metricsDoNotSeeEachOther() {
        store.put(WearMetrics.HEART_RATE, hr(60_000, 60))
        store.put(WearMetrics.SLEEP_MINUTES, minute(60_000, samples = 10))

        assertEquals(1L, store.count(WearMetrics.HEART_RATE))
        assertEquals(1L, store.count(WearMetrics.SLEEP_MINUTES))
        store.prune(WearMetrics.HEART_RATE, nowEpochMillis = 60_001 + WearMetric.WEEK_MILLIS)
        assertEquals(0L, store.count(WearMetrics.HEART_RATE))
        assertEquals(1L, store.count(WearMetrics.SLEEP_MINUTES))
    }

    @Test
    fun pruningKeepsTheRetentionWindow() {
        val now = 10 * WearMetric.WEEK_MILLIS
        store.putAll(WearMetrics.HEART_RATE, listOf(hr(now - WearMetric.WEEK_MILLIS - 1, 60), hr(now - WearMetric.WEEK_MILLIS, 61)))
        store.prune(WearMetrics.HEART_RATE, now)
        assertEquals(listOf(hr(now - WearMetric.WEEK_MILLIS, 61)), store.since(WearMetrics.HEART_RATE, 0, 10))
    }

    @Test
    fun aRowThisBuildCannotReadIsSkippedNotFatal() {
        store.put(WearMetrics.HEART_RATE, hr(1_000, 60))
        insertRaw(WearMetrics.HEART_RATE.key, 2_000L, "HR from a future build")
        assertEquals(listOf(hr(1_000, 60)), store.since(WearMetrics.HEART_RATE, 0, 10))
        assertNull(store.latest(WearMetrics.HEART_RATE))
    }

    @Test
    fun aPageSaysMoreFromTheRowsNotFromWhatDecoded() {
        store.putAll(WearMetrics.HEART_RATE, listOf(hr(1_000, 60), hr(3_000, 62), hr(4_000, 63)))
        insertRaw(WearMetrics.HEART_RATE.key, 2_000L, "HR from a future build")

        // The unreadable row neither takes a place in the page nor ends the paging before 4 000.
        val first = store.page(WearMetrics.HEART_RATE, 0, limit = 2)
        assertEquals(listOf(hr(1_000, 60), hr(3_000, 62)), first.items)
        assertTrue(first.more)

        val rest = store.page(WearMetrics.HEART_RATE, first.items.last().epochMillis, limit = 2)
        assertEquals(listOf(hr(4_000, 63)), rest.items)
        assertFalse(rest.more)
    }

    @Test
    fun aPageOfOnlyUnreadableRowsReadsOnToTheNextValue() {
        for (at in 1_000L..1_005L) insertRaw(WearMetrics.HEART_RATE.key, at, "HR from a future build")
        store.put(WearMetrics.HEART_RATE, hr(9_000, 70))

        val page = store.page(WearMetrics.HEART_RATE, 0, limit = 2)

        assertEquals(listOf(hr(9_000, 70)), page.items)
        assertFalse(page.more)
    }

    @Test
    fun aFailedWriteIsCountedAndAnsweredNotThrown() {
        store.writableDatabase.execSQL("DROP TABLE ${MetricStore.TABLE}")

        assertFalse(store.put(WearMetrics.HEART_RATE, hr(1_000, 60)))
        assertFalse(store.putAll(WearMetrics.STEPS, listOf(WearLinkProtocol.StepMinute(60_000, 12)), "steps", "1,12,60000"))

        assertEquals(2L, store.writeFailures)
        assertEquals("SQLiteException", store.lastWriteFailure)
        // The batch and its state are one transaction: the state was not written without the rows.
        assertNull(store.state("steps"))
    }

    @Test
    fun aFileStoreRunsInWriteAheadLoggingAndTakesWritersOnSeveralThreads() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(FILE_DB)
        val file = MetricStore(context, FILE_DB)
        try {
            assertTrue(file.writableDatabase.isWriteAheadLoggingEnabled)
            val threads = (0 until WRITERS).map { writer ->
                Thread {
                    for (n in 0 until ROWS_PER_WRITER) {
                        val at = (writer * ROWS_PER_WRITER + n) * 1_000L
                        file.putAll(WearMetrics.STEPS, listOf(WearLinkProtocol.StepMinute(at, 1)), "steps", "writer $writer")
                        if (n % 10 == 0) file.page(WearMetrics.STEPS, 0, limit = 50)
                    }
                }
            }
            threads.forEach(Thread::start)
            threads.forEach { it.join() }

            assertEquals(0L, file.writeFailures)
            assertEquals((WRITERS * ROWS_PER_WRITER).toLong(), file.count(WearMetrics.STEPS))
        } finally {
            file.close()
            context.deleteDatabase(FILE_DB)
        }
    }

    private fun insertRaw(key: String, at: Long, line: String) {
        store.writableDatabase.insert(
            MetricStore.TABLE,
            null,
            ContentValues().apply {
                put("metric", key)
                put("time_ms", at)
                put("line", line)
                put("seq", RAW_SEQ + at)
            },
        )
    }

    private fun hr(at: Long, bpm: Int) = WearLinkProtocol.HeartRateSample(at, bpm)

    private fun minute(at: Long, samples: Int) = WearLinkProtocol.SleepMinute(
        epochMillis = at,
        kind = WearLinkProtocol.MinuteKind.RAW,
        offsetSeconds = 0,
        flags = 0,
        sampleCount = samples,
        movement10 = 0,
        bpm = null,
        heartRateSd10 = null,
        heartRateSamples = 0,
        meanMilliG = intArrayOf(0, 0, 1000),
        sdMilliG = intArrayOf(0, 0, 0),
        zAngleMin = null,
        zAngleMax = null,
        zAngleDelta10 = null,
    )

    private companion object {
        /** Its own name: on a watch the test runs inside the installed app, whose real store must stay untouched. */
        const val FILE_DB = "test_metrics_wal.db"
        const val WRITERS = 4
        const val ROWS_PER_WRITER = 100

        /** Far above what the tests' own writes take, so a raw row never shares a number with one. */
        const val RAW_SEQ = 1_000_000L
    }
}
