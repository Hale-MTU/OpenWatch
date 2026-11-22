package tech.mmarca.openwatch.data.repository

import androidx.health.connect.client.records.Record
import java.time.Instant
import kotlin.reflect.KClass

/**
 * The Health Connect writes a device makes, and the one read they need: what this app itself
 * already stored. An interface so the write helpers (`ImportedRecordWrites.kt`) are tested
 * against a fake that behaves the way Health Connect was measured to (`ClientRecordVersionTest`).
 */
interface HealthRecordStore {

    /**
     * Inserts [records]. On the platform's Health Connect (Android 14 and newer) a stored
     * `clientRecordId` is replaced at an equal or higher version and kept at a lower one; the
     * Health Connect APK on older Android may refuse the whole batch instead.
     */
    suspend fun insertImportedRecords(records: List<Record>)

    /** Deletes this app's records of [recordType] by `clientRecordId`. Unknown ids are ignored. */
    suspend fun deleteImportedRecordsByClientIds(recordType: KClass<out Record>, clientRecordIds: List<String>)

    /**
     * The `clientRecordId` and version of each of this app's own [recordType] records in
     * `[start, end]`. Needs no read grant, in the foreground or the background: an app always
     * sees what it wrote.
     */
    suspend fun ownClientRecordVersions(recordType: KClass<out Record>, start: Instant, end: Instant): Map<String, Long>
}
