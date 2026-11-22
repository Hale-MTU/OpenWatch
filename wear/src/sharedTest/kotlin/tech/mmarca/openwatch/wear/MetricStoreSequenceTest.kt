package tech.mmarca.openwatch.wear

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

/**
 * The store's sequence numbers, against real SQLite: what the phone's sequence cursor rests
 * on, and the version 3 migration that adds them to a watch's existing rows. File databases
 * under their own names: on a watch the test runs inside the installed app.
 */
@RunWith(AndroidJUnit4::class)
class MetricStoreSequenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = ArrayList<MetricStore>()

    @After
    fun close() {
        opened.forEach(MetricStore::close)
        context.deleteDatabase(FILE_DB)
    }

    private fun open(): MetricStore = MetricStore(context, FILE_DB).also { opened += it }

    @Test
    fun aRowWrittenLateOrRewrittenComesAgainAfterTheRowsAlreadyRead() {
        val store = open()
        store.putAll(WearMetrics.STEPS, listOf(step(120_000, 10), step(180_000, 20)))
        val first = store.changes(WearMetrics.STEPS, afterSeq = 0, limit = 10)
        assertEquals(listOf("ST 120000 10", "ST 180000 20"), first.lines)
        assertFalse(first.more)

        // The newest minute grows, and a minute behind every row read so far arrives late.
        store.put(WearMetrics.STEPS, step(180_000, 35))
        store.put(WearMetrics.STEPS, step(60_000, 5))
        val next = store.changes(WearMetrics.STEPS, afterSeq = first.lastSeq, limit = 10)

        assertEquals(listOf("ST 180000 35", "ST 60000 5"), next.lines)
        assertTrue(next.lastSeq > first.lastSeq)
        // Read by time, the store is as it always was.
        assertEquals(listOf(step(60_000, 5), step(120_000, 10), step(180_000, 35)), store.since(WearMetrics.STEPS, 0, 10))
    }

    @Test
    fun aValueKeptAsItWasIsNotSeenAgainAndMetricsPageApart() {
        val store = open()
        store.put(WearMetrics.HEART_RATE, hr(1_000, 60))
        val read = store.changes(WearMetrics.HEART_RATE, afterSeq = 0, limit = 10)
        // A sample is final: the second one at its time is dropped, and steps are another series.
        store.put(WearMetrics.HEART_RATE, hr(1_000, 99))
        store.put(WearMetrics.STEPS, step(60_000, 1))

        val after = store.changes(WearMetrics.HEART_RATE, afterSeq = read.lastSeq, limit = 10)
        assertEquals(emptyList<String>(), after.lines)
        assertEquals(read.lastSeq, after.lastSeq)
        assertEquals(listOf("ST 60000 1"), store.changes(WearMetrics.STEPS, afterSeq = 0, limit = 10).lines)
    }

    @Test
    fun aPageIsLimitedAndSaysWhenMoreFollow() {
        val store = open()
        store.putAll(WearMetrics.HEART_RATE, (1..5).map { hr(it * 1_000L, 60 + it) })

        val page = store.changes(WearMetrics.HEART_RATE, afterSeq = 0, limit = 3)
        assertEquals(3, page.lines.size)
        assertTrue(page.more)
        val rest = store.changes(WearMetrics.HEART_RATE, afterSeq = page.lastSeq, limit = 3)
        assertEquals(listOf("HR 4000 64", "HR 5000 65"), rest.lines)
        assertFalse(rest.more)
    }

    @Test
    fun theNumbersNeverGoBackAcrossPruningAndARestartAndTheIdStays() {
        val store = open()
        store.putAll(WearMetrics.HEART_RATE, listOf(hr(1_000, 60), hr(2_000, 61)))
        val high = store.changes(WearMetrics.HEART_RATE, afterSeq = 0, limit = 10).lastSeq
        val id = store.storeId
        // The newest numbers go with the rows.
        store.prune(WearMetrics.HEART_RATE, nowEpochMillis = 3_000 + WearMetric.WEEK_MILLIS)
        store.close()

        val again = open()
        again.put(WearMetrics.HEART_RATE, hr(4_000, 62))

        assertEquals(listOf("HR 4000 62"), again.changes(WearMetrics.HEART_RATE, afterSeq = high, limit = 10).lines)
        assertEquals(id, again.storeId)
    }

    @Test
    fun aNewDatabaseIsANewNumbering() {
        val first = open().storeId
        opened.forEach(MetricStore::close)
        opened.clear()
        context.deleteDatabase(FILE_DB)

        assertNotEquals(first, open().storeId)
    }

    @Test
    fun versionTwoRowsAreNumberedInTimeOrderAndKeepEverythingElse() {
        context.deleteDatabase(FILE_DB)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(FILE_DB), null).use { db ->
            // The schema of version 2, as the watch's builds before this one created it.
            db.execSQL("CREATE TABLE rows (metric TEXT NOT NULL, time_ms INTEGER NOT NULL, line TEXT NOT NULL, PRIMARY KEY (metric, time_ms)) WITHOUT ROWID")
            db.execSQL("CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("INSERT INTO rows VALUES ('st', 120000, 'ST 120000 12'), ('hr', 120000, 'HR 120000 64'), ('hr', 10000, 'HR 10000 61'), ('st', 60000, 'ST 60000 8')")
            db.execSQL("INSERT INTO state VALUES ('steps', '1,20,120000')")
            db.version = 2
        }

        val store = open()

        // Time order, and within one millisecond by metric.
        assertEquals(listOf("HR 10000 61", "HR 120000 64"), store.changes(WearMetrics.HEART_RATE, 0, 10).lines)
        val steps = store.changes(WearMetrics.STEPS, 0, 10)
        assertEquals(listOf("ST 60000 8", "ST 120000 12"), steps.lines)
        assertEquals(4L, steps.lastSeq)
        assertEquals(listOf(hr(10_000, 61), hr(120_000, 64)), store.since(WearMetrics.HEART_RATE, 0, 10))
        assertEquals(listOf(step(60_000, 8), step(120_000, 12)), store.page(WearMetrics.STEPS, 0, 10).items)
        assertEquals("1,20,120000", store.state("steps"))
        assertEquals(16, store.storeId.length)
        assertEquals(MetricStore.DB_VERSION, store.readableDatabase.version)

        // The next write goes after every migrated row.
        store.put(WearMetrics.STEPS, step(120_000, 15))
        assertEquals(listOf("ST 120000 15"), store.changes(WearMetrics.STEPS, afterSeq = 4, limit = 10).lines)
    }

    private fun hr(at: Long, bpm: Int) = WearLinkProtocol.HeartRateSample(at, bpm)

    private fun step(at: Long, steps: Int) = WearLinkProtocol.StepMinute(at, steps)

    private companion object {
        const val FILE_DB = "test_metrics_seq.db"
    }
}
