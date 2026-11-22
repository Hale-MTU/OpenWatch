package tech.mmarca.openwatch.devices.wearos

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import tech.mmarca.openwatch.core.presentation.isPermissionFailure
import tech.mmarca.openwatch.data.repository.ImportedRecordRepository
import tech.mmarca.openwatch.data.repository.RecordWrite
import tech.mmarca.openwatch.data.repository.insertWithOutcomes
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.wearlink.WearLinkSince

/** A pull again after one link failure; the sync service's own policy. */
internal interface WearOsLinkRetry {
    suspend fun <T> run(pull: suspend () -> T): T
}

/**
 * Reads a series from the watch page by page with `SINCE`: from a start cursor, each page handed
 * on before the next is asked, until the watch has no more.
 */
internal class WearOsSeriesReader(private val nodePort: WearOsNodePort, private val retry: WearOsLinkRetry) {

    /**
     * Every page of [series] from [start], each given to [onPage] before the next is asked; [onPage]
     * returns false to stop there. False when no bonded watch matches.
     */
    suspend fun <T : Any> pages(
        device: BleSensorDevice,
        series: WearSeries<T>,
        start: String,
        onPage: suspend (WearOsSeriesPage<T>) -> Boolean,
    ): Boolean {
        var cursor = start
        var pages = 0
        while (true) {
            val page = retry.run { nodePort.pullSeries(device.address, device.bluetoothName, series, cursor) } ?: return false
            if (!onPage(page)) return true
            // A page that did not move the cursor would be asked again forever.
            if (!page.hasMore || page.cursor == cursor || ++pages >= MAX_PAGES) return true
            cursor = page.cursor
        }
    }

    /**
     * The values of [series] newer than [after]: a window an estimate is made over, read from the
     * time position there. [onPage] runs once per page. Null when no bonded watch matches.
     */
    suspend fun <T : Any> newerThan(
        device: BleSensorDevice,
        series: WearSeries<T>,
        after: Instant,
        onPage: () -> Unit = {},
    ): List<T>? {
        val values = ArrayList<T>()
        val found = pages(device, series, WearLinkSince.afterTime(after.toEpochMilli())) { page ->
            onPage()
            page.values.filterTo(values) { series.timeOf(it).isAfter(after) }
            true
        }
        return values.takeIf { found }
    }

    private companion object {
        /** A safety net against a watch that always says "more": two million rows. */
        const val MAX_PAGES = 1000
    }
}

/**
 * Writes a series whose values are one Health Connect record each ([WearSeries.writes]: the HRV,
 * the breathing rate, the SpO2), page by page from the watch's cursor, which moves after each
 * page is written, so a sync that dies mid-way resumes where it stopped and a row the watch
 * wrote late or rewrote comes with the next page.
 *
 * The first pull hands the series' time cursor over to the sequence ([WearLinkSince.afterTime]):
 * every row newer than it comes, and every row the watch wrote after the first of those. That
 * re-pulls nothing already written and leaves out only rows the time cursor had passed.
 *
 * Without the write grant nothing is written and the cursor stays, so the values wait on the
 * watch (it keeps a week) for the next sync after the grant. That never fails the sync.
 */
internal class WearOsSeriesSync(
    private val importRepository: ImportedRecordRepository,
    private val granted: suspend () -> Set<String>,
    private val cursors: WearOsSyncCursorStore,
    private val zone: () -> ZoneId,
) {

    /** [written] records, and whether the missing write grant [withheld] some values. */
    class Outcome(val written: Int, val withheld: Boolean)

    /**
     * Null when no bonded watch matches. A failure other than a missing grant throws.
     * [timeCursor] is the series' time cursor: where the first pull starts, and what it keeps
     * moving to the newest value written.
     */
    suspend fun <T : Any> sync(
        device: BleSensorDevice,
        series: WearSeries<T>,
        timeCursor: TimeCursor,
        reader: WearOsSeriesReader,
    ): Outcome? {
        val writes = checkNotNull(series.writes) { "$series has a pass of its own" }
        val start = cursors.seriesCursor(device.id, series.key) ?: WearLinkSince.afterTime(timeCursor.get().toEpochMilli())
        val allowed = writes.permission in granted()
        var written = 0
        var withheld = false
        val found = reader.pages(device, series, start) { page ->
            if (page.values.isNotEmpty()) {
                if (!allowed) {
                    withheld = true
                    return@pages false
                }
                val records = page.values.mapNotNull { writes.record(it, zone(), device.idSalt) }
                if (records.size < page.values.size) {
                    WearOsLog.log("${writes.label}: ${page.values.size - records.size} value(s) not written, Health Connect refused the value")
                }
                written += try {
                    importRepository.insertWithOutcomes(records).count { it == RecordWrite.WRITTEN }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (error !is SecurityException && !error.isPermissionFailure()) throw error
                    withheld = true
                    return@pages false
                }
                val newest = page.values.maxOf(series.timeOf)
                if (newest.isAfter(timeCursor.get())) timeCursor.set(newest)
            }
            cursors.setSeriesCursor(device.id, series.key, page.cursor)
            true
        }
        return Outcome(written, withheld).takeIf { found }
    }

    /** A series' time cursor in [WearOsSyncCursorStore]. */
    class TimeCursor(val get: () -> Instant, val set: (Instant) -> Unit)
}
