package tech.mmarca.openwatch.wear

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.atomic.AtomicLong
import tech.mmarca.openwatch.wearlink.Page

/**
 * Everything the watch has recorded and the phone may not have fetched yet,
 * for every [WearMetric], in one table: one row per metric and time, the
 * value kept as its protocol line. Plain SQLite: the watch has no Room, and
 * a three-column table needs no migrations; a new metric is a new key, not
 * a new table.
 *
 * The phone keeps its own cursors and the watch keeps each metric's
 * retention of history; nothing is deleted on the phone's behalf.
 *
 * A second table keeps a recorder's own state by key, so a recorder can
 * write it in the same transaction as its rows.
 *
 * One instance per process ([OpenWatchWearApplication]), opened in
 * write-ahead logging, so the link, the tiles and the screens read while a
 * recorder writes. Every write goes through one guarded path: one writer at a
 * time, each write one transaction, and a failure (a full disk, a corrupt
 * file) counted in [writeFailures] and answered with false instead of thrown
 * on a recorder's thread, where it would end the process. Reads still throw.
 */
class MetricStore(context: Context, name: String? = DB_NAME) : SQLiteOpenHelper(context, name, null, DB_VERSION) {

    init {
        // A journal mode, not a format: the file, its tables and its rows stay as they were.
        setWriteAheadLoggingEnabled(true)
    }

    private val writeLock = Any()
    private val failedWrites = AtomicLong()

    /** Writes that failed since the process started; the debug snapshots report it. */
    val writeFailures: Long
        get() = failedWrites.get()

    /** The kind of the last failed write (its exception's class), or null when none failed. */
    @Volatile
    var lastWriteFailure: String? = null
        private set

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE (" +
                "$COL_METRIC TEXT NOT NULL, " +
                "$COL_TIME INTEGER NOT NULL, " +
                "$COL_LINE TEXT NOT NULL, " +
                "PRIMARY KEY ($COL_METRIC, $COL_TIME)) WITHOUT ROWID",
        )
        createStateTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createStateTable(db)
    }

    private fun createStateTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS $STATE_TABLE ($COL_KEY TEXT PRIMARY KEY, $COL_VALUE TEXT NOT NULL)")
    }

    /** Stores one value; one already at its time stays or is replaced as [WearMetric.onSameTime] says. False when the write failed. */
    fun <T> put(metric: WearMetric<T>, value: T): Boolean = write { db -> insert(db, metric, value) }

    /** Stores several values in one transaction: all of them, or none and false. */
    fun <T> putAll(metric: WearMetric<T>, values: Iterable<T>): Boolean = write { db ->
        for (value in values) insert(db, metric, value)
    }

    /** Stores [values] and sets the state [key] to [state], in one transaction: both, or neither and false. */
    fun <T> putAll(metric: WearMetric<T>, values: Iterable<T>, key: String, state: String): Boolean = write { db ->
        for (value in values) insert(db, metric, value)
        putState(db, key, state)
    }

    /** The state stored under [key], or null when there is none. */
    fun state(key: String): String? =
        readableDatabase.query(STATE_TABLE, arrayOf(COL_VALUE), "$COL_KEY = ?", arrayOf(key), null, null, null)
            .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    /** Stores [value] under [key] alone, as a running workout's checkpoint. */
    fun setState(key: String, value: String): Boolean = write { db -> putState(db, key, value) }

    fun clearState(key: String): Boolean = write { db -> db.delete(STATE_TABLE, "$COL_KEY = ?", arrayOf(key)) }

    /**
     * Up to [limit] values newer than [epochMillis] and older than
     * [untilEpochMillis], oldest first. Rows this build cannot read are skipped.
     */
    fun <T> since(metric: WearMetric<T>, epochMillis: Long, limit: Int, untilEpochMillis: Long = Long.MAX_VALUE): List<T> =
        readableDatabase.query(
            TABLE,
            arrayOf(COL_LINE),
            "$COL_METRIC = ? AND $COL_TIME > ? AND $COL_TIME < ?",
            arrayOf(metric.key, epochMillis.toString(), untilEpochMillis.toString()),
            null,
            null,
            "$COL_TIME ASC",
            limit.coerceAtLeast(1).toString(),
        ).use { cursor ->
            buildList(cursor.count) {
                while (cursor.moveToNext()) metric.decode(cursor.getString(0))?.let(::add)
            }
        }

    /**
     * One page of the link's answer: up to [limit] values newer than
     * [epochMillis], oldest first, and whether the store holds rows after
     * them. "More" comes from the rows, not from what decoded: a row this
     * build cannot read is skipped and the page reads on past it, so it can
     * neither end the paging early nor leave a page short while rows remain.
     */
    fun <T> page(metric: WearMetric<T>, epochMillis: Long, limit: Int): Page<T> {
        val wanted = limit.coerceAtLeast(1)
        val items = ArrayList<T>(minOf(wanted, PAGE_CAPACITY))
        var after = epochMillis
        while (true) {
            val missing = wanted - items.size
            // One row more than still missing tells whether the store goes on after this page.
            val rows = rowsAfter(metric, after, missing + 1)
            val taken = rows.take(missing)
            for (row in taken) metric.decode(row.line)?.let(items::add)
            if (rows.size <= taken.size) return Page(items, more = false)
            if (items.size == wanted) return Page(items, more = true)
            after = taken.last().epochMillis
        }
    }

    /** Deletes the rows of [metric] from [fromEpochMillis] to [toEpochMillis], both included. */
    fun deleteRange(metric: WearMetric<*>, fromEpochMillis: Long, toEpochMillis: Long): Boolean = write { db ->
        db.delete(
            TABLE,
            "$COL_METRIC = ? AND $COL_TIME >= ? AND $COL_TIME <= ?",
            arrayOf(metric.key, fromEpochMillis.toString(), toEpochMillis.toString()),
        )
    }

    /** The newest value, or null when there is none or this build cannot read it. */
    fun <T> latest(metric: WearMetric<T>): T? =
        readableDatabase.query(
            TABLE,
            arrayOf(COL_LINE),
            "$COL_METRIC = ?",
            arrayOf(metric.key),
            null,
            null,
            "$COL_TIME DESC",
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) metric.decode(cursor.getString(0)) else null }

    fun count(metric: WearMetric<*>): Long =
        DatabaseUtils.queryNumEntries(readableDatabase, TABLE, "$COL_METRIC = ?", arrayOf(metric.key))

    /** Drops what is older than [metric]'s retention as of [nowEpochMillis]. */
    fun prune(metric: WearMetric<*>, nowEpochMillis: Long): Boolean = write { db ->
        db.delete(
            TABLE,
            "$COL_METRIC = ? AND $COL_TIME < ?",
            arrayOf(metric.key, (nowEpochMillis - metric.retentionMillis).toString()),
        )
    }

    /**
     * The one way a write reaches the database: under [writeLock], so two
     * recorders' writes run one after the other and a batch is never split by
     * another's, in one transaction, and with a failure counted and answered
     * with false.
     */
    private inline fun write(block: (SQLiteDatabase) -> Unit): Boolean = synchronized(writeLock) {
        try {
            val db = writableDatabase
            db.beginTransactionNonExclusive()
            try {
                block(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            true
        } catch (e: SQLiteException) {
            failedWrites.incrementAndGet()
            lastWriteFailure = e.javaClass.simpleName
            false
        }
    }

    private class Row(val epochMillis: Long, val line: String)

    /** Up to [limit] raw rows of [metric] newer than [epochMillis], oldest first, decoded or not. */
    private fun rowsAfter(metric: WearMetric<*>, epochMillis: Long, limit: Int): List<Row> =
        readableDatabase.query(
            TABLE,
            arrayOf(COL_TIME, COL_LINE),
            "$COL_METRIC = ? AND $COL_TIME > ?",
            arrayOf(metric.key, epochMillis.toString()),
            null,
            null,
            "$COL_TIME ASC",
            limit.toString(),
        ).use { cursor ->
            buildList(cursor.count) { while (cursor.moveToNext()) add(Row(cursor.getLong(0), cursor.getString(1))) }
        }

    private fun <T> insert(db: SQLiteDatabase, metric: WearMetric<T>, value: T) {
        val values = ContentValues(3).apply {
            put(COL_METRIC, metric.key)
            put(COL_TIME, metric.timeOf(value))
            put(COL_LINE, metric.encode(value))
        }
        val conflict = when (metric.onSameTime) {
            WearMetric.OnSameTime.KEEP -> SQLiteDatabase.CONFLICT_IGNORE
            WearMetric.OnSameTime.REPLACE -> SQLiteDatabase.CONFLICT_REPLACE
        }
        db.insertWithOnConflict(TABLE, null, values, conflict)
    }

    private fun putState(db: SQLiteDatabase, key: String, value: String) {
        val row = ContentValues(2).apply {
            put(COL_KEY, key)
            put(COL_VALUE, value)
        }
        db.insertWithOnConflict(STATE_TABLE, null, row, SQLiteDatabase.CONFLICT_REPLACE)
    }

    companion object {
        const val DB_NAME = "metrics.db"
        private const val DB_VERSION = 2
        const val TABLE = "rows"
        private const val COL_METRIC = "metric"
        private const val COL_TIME = "time_ms"
        private const val COL_LINE = "line"
        private const val STATE_TABLE = "state"
        private const val COL_KEY = "key"
        private const val COL_VALUE = "value"

        /** What a page reserves up front; a larger one grows as it fills. */
        private const val PAGE_CAPACITY = 1024
    }
}
