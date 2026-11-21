package tech.mmarca.openwatch.data.repository

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import kotlinx.coroutines.CancellationException
import tech.mmarca.openwatch.healthconnect.MaxRecordsPerInsert

/** What became of one record in [insertWithOutcomes]. */
enum class RecordWrite { WRITTEN, PRESENT, FAILED }

/**
 * Writes [records] in batches of at most [MaxRecordsPerInsert]. The Health Connect APK on
 * Android 13 and older is taken to refuse a whole batch when one `clientRecordId` is already
 * there; then each record of that batch is written alone, so the new ones still land.
 * Batching here keeps that fallback to the one batch that was refused.
 *
 * The platform's Health Connect (Android 14 and newer) refuses nothing: an equal version
 * replaces the stored record and a lower one is silently ignored (`ClientRecordVersionTest`,
 * measured on Android 17). There every record reads as [RecordWrite.WRITTEN], present or not,
 * and a record rewritten at the same version changes; only a lower version keeps the old one.
 *
 * Returns what became of each record, in order. Any other failure of a
 * batch is thrown. Any other failure of a single record goes to [onFailure],
 * which throws it unless the caller says otherwise.
 */
suspend fun ImportedRecordRepository.insertWithOutcomes(
    records: List<Record>,
    onFailure: (Record, Exception) -> Unit = { _, error -> throw error },
): List<RecordWrite> = records.chunked(MaxRecordsPerInsert).flatMap { insertBatchWithOutcomes(it, onFailure) }

private suspend fun ImportedRecordRepository.insertBatchWithOutcomes(
    records: List<Record>,
    onFailure: (Record, Exception) -> Unit,
): List<RecordWrite> {
    if (records.isEmpty()) return emptyList()
    try {
        insertImportedRecords(records)
        return List(records.size) { RecordWrite.WRITTEN }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (!error.isDuplicateClientRecordFailure()) throw error
        // One record: the refusal already says it is present.
        if (records.size == 1) return listOf(RecordWrite.PRESENT)
    }
    return records.map { record ->
        try {
            insertImportedRecords(listOf(record))
            RecordWrite.WRITTEN
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error.isDuplicateClientRecordFailure()) {
                RecordWrite.PRESENT
            } else {
                onFailure(record, error)
                RecordWrite.FAILED
            }
        }
    }
}

/**
 * [records] split by whether this app may write their type: the ones whose Health Connect
 * write permission is in [granted], and the permissions of the rest. A write that is not
 * granted fails the whole request, so it is left out before the request, not after.
 */
fun partitionByWriteGrant(records: List<Record>, granted: Set<String>): Pair<List<Record>, Set<String>> {
    val withheld = sortedSetOf<String>()
    val allowed = records.filter { record ->
        val permission = HealthPermission.getWritePermission(record::class)
        (permission in granted).also { if (!it) withheld += permission }
    }
    return allowed to withheld
}

/** [insertWithOutcomes], counting the records that landed. A record already present does not count. */
suspend fun ImportedRecordRepository.insertSkippingPresent(records: List<Record>): Int =
    insertWithOutcomes(records).count { it == RecordWrite.WRITTEN }

/** True when Health Connect refused a write because the `clientRecordId` is already there. */
internal fun Throwable.isDuplicateClientRecordFailure(): Boolean {
    val text = generateSequence(this) { it.cause }
        .joinToString(" ") { listOfNotNull(it.message, it::class.simpleName).joinToString(" ") }
        .lowercase()
    return "clientrecordid" in text ||
        "client record id" in text ||
        (("duplicate" in text || "already exist" in text || "already exists" in text) && "record" in text)
}
