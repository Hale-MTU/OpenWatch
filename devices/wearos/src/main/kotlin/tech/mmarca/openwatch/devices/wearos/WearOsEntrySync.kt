package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.permission.HealthPermission
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import tech.mmarca.openwatch.core.presentation.isPermissionFailure
import tech.mmarca.openwatch.data.repository.HealthRecordStore
import tech.mmarca.openwatch.data.repository.RecordWrite
import tech.mmarca.openwatch.data.repository.insertNewer
import tech.mmarca.openwatch.data.repository.partitionByWriteGrant
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.healthconnect.HealthConnectReads
import tech.mmarca.openwatch.wearlink.WearLinkSince

/**
 * Writes the entries made on the watch ([WearOsSeries.ENTRIES], `SINCE lg`) to
 * Health Connect, page by page by the watch's cursor ([WearOsEntryImport]):
 * what the wearer logged in the quick log and what a spot measurement took.
 * The first pull asks for everything the watch keeps (a month).
 *
 * The entries are of several types under several grants. Every page is read
 * and the types whose write grant is in are written ([insertNewer], so an
 * entry written before is not written again); an entry whose grant is
 * missing is held back, and from then on the cursor stays where it was, so
 * the held entries come again with the next sync, after the grant, while the
 * others are written as they come. An entry this Health Connect cannot keep
 * at all (a breathing session where it stores no mindfulness) or a value it
 * would refuse is dropped, and the cursor moves past it. The grants held back
 * go to the sync's result, so the watch's card asks for them; that never
 * fails the sync.
 */
internal class WearOsEntrySync(
    private val importRepository: HealthRecordStore,
    private val healthConnect: HealthConnectReads,
    private val cursors: WearOsSyncCursorStore,
    private val zone: () -> ZoneId,
) {

    /** [written] records, and the write grants the entries held back wait for. */
    class Outcome(val written: Int, val withheld: Set<String>)

    /** Null when no bonded watch matches. A failure other than a missing grant throws. */
    suspend fun sync(device: BleSensorDevice, reader: WearOsSeriesReader, onPage: () -> Unit = {}): Outcome? {
        val series = WearOsSeries.ENTRIES
        val start = cursors.seriesCursor(device.id, series.key) ?: WearLinkSince.afterTime(0)
        val granted = healthConnect.grantedPermissions()
        val mindfulness = healthConnect.isMindfulnessAvailable()
        var written = 0
        var dropped = 0
        val withheld = sortedSetOf<String>()
        val found = reader.pages(device, series, start) { page ->
            onPage()
            val storable = page.values.filter { WearOsEntryImport.isStorable(it.type, mindfulness) }
            val records = storable.mapNotNull { WearOsEntryImport.record(it, zone(), device.idSalt) }
            dropped += page.values.size - records.size
            val (allowed, missing) = partitionByWriteGrant(records, granted)
            withheld += missing
            if (allowed.isNotEmpty()) {
                written += try {
                    importRepository.insertNewer(allowed).count { it == RecordWrite.WRITTEN }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (error !is SecurityException && !error.isPermissionFailure()) throw error
                    // A grant taken back since it was read: what this page tried waits with the rest.
                    allowed.mapTo(withheld) { HealthPermission.getWritePermission(it::class) }
                    0
                }
            }
            // Once an entry is held back the cursor stays before it, so it comes again; the next pages are still written.
            if (withheld.isEmpty()) cursors.setSeriesCursor(device.id, series.key, page.cursor)
            true
        }
        WearOsLog.log("entries: $written written, $dropped dropped, ${withheld.size} grant(s) missing")
        return Outcome(written, withheld).takeIf { found }
    }
}
