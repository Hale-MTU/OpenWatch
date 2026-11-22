package tech.mmarca.openwatch.data.repository

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import java.time.Instant
import kotlinx.coroutines.CancellationException
import tech.mmarca.openwatch.healthconnect.HealthConnectSyncDisabledException
import tech.mmarca.openwatch.healthconnect.MaxRecordsPerInsert
import tech.mmarca.openwatch.healthconnect.isHealthConnectRateLimit

/** What became of one record in [insertWithOutcomes]. */
enum class RecordWrite { WRITTEN, PRESENT }

/**
 * Writes [records] in batches of at most [MaxRecordsPerInsert] and says what became of each, in
 * order.
 *
 * The platform's Health Connect (Android 14 and newer) refuses nothing: an equal version
 * replaces the stored record and a lower one is silently kept out (`ClientRecordVersionTest`,
 * measured on Android 17). There every record reads as [RecordWrite.WRITTEN], stored or not.
 *
 * The Health Connect APK on Android 13 and older is taken to refuse a whole batch when one
 * `clientRecordId` is already there. A refused batch is diagnosed by reading what this app
 * holds, never by the refusal's text: the records stored at their version or newer are
 * [RecordWrite.PRESENT], and the rest is written again. A provider that refuses even a newer
 * version keeps the stored record, which then reads as present too. One read and at most two
 * more writes per refused batch, where writing each record alone cost a call per record.
 *
 * A refusal the read cannot explain, a missing grant, a paused sync or a spent quota is thrown.
 */
suspend fun HealthRecordStore.insertWithOutcomes(records: List<Record>): List<RecordWrite> =
    records.chunked(MaxRecordsPerInsert).flatMap { insertBatchWithOutcomes(it) }

private suspend fun HealthRecordStore.insertBatchWithOutcomes(records: List<Record>): List<RecordWrite> {
    if (records.isEmpty()) return emptyList()
    val refusal = refusalOf { insertImportedRecords(records) } ?: return List(records.size) { RecordWrite.WRITTEN }
    if (!refusal.mayConcernStoredRecords()) throw refusal
    val stored = storedVersions(records) ?: throw refusal
    if (records.none { it.clientId in stored }) throw refusal
    val outcomes = MutableList(records.size) { RecordWrite.WRITTEN }
    val rest = records.indices.filter { index ->
        val version = stored[records[index].clientId]
        val present = version != null && version >= records[index].metadata.clientRecordVersion
        if (present) outcomes[index] = RecordWrite.PRESENT
        !present
    }
    if (rest.isEmpty()) return outcomes
    val second = refusalOf { insertImportedRecords(rest.map(records::get)) } ?: return outcomes
    if (!second.mayConcernStoredRecords()) throw second
    // This provider refuses a newer version of a stored id too: the stored one stays.
    val fresh = rest.filter { index ->
        (records[index].clientId !in stored).also { isNew -> if (!isNew) outcomes[index] = RecordWrite.PRESENT }
    }
    if (fresh.isNotEmpty()) insertImportedRecords(fresh.map(records::get))
    return outcomes
}

/**
 * Writes [record] over the stored one with the same `clientRecordId`. A higher version replaces
 * it on its own; a provider that refuses anyway has the stored one deleted first, once.
 */
suspend fun HealthRecordStore.replaceImportedRecord(record: Record) {
    val refusal = refusalOf { insertImportedRecords(listOf(record)) } ?: return
    if (!refusal.mayConcernStoredRecords() || storedVersions(listOf(record))?.contains(record.clientId) != true) throw refusal
    deleteImportedRecordsByClientIds(record::class, listOf(record.clientId))
    insertImportedRecords(listOf(record))
}

/** [insertWithOutcomes], counting the records that landed. A record already present does not count. */
suspend fun HealthRecordStore.insertSkippingPresent(records: List<Record>): Int =
    insertWithOutcomes(records).count { it == RecordWrite.WRITTEN }

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

/** The failure of [write], or null when it went through. Cancellation is never a refusal. */
private suspend fun refusalOf(write: suspend () -> Unit): Exception? = try {
    write()
    null
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    error
}

/** A failure that is not about which records are stored: a grant, the pause switch, the quota. */
private fun Throwable.mayConcernStoredRecords(): Boolean =
    this !is HealthConnectSyncDisabledException &&
        !isHealthConnectRateLimit() &&
        generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }.none { it is SecurityException }

/**
 * The version this app holds of each of [records]' ids, by one read per record type over their
 * span; null when a record's type has no known span, so nothing can be told about it.
 */
private suspend fun HealthRecordStore.storedVersions(records: List<Record>): Map<String, Long>? {
    val versions = mutableMapOf<String, Long>()
    for ((type, ofType) in records.groupBy { it::class }) {
        val spans = ofType.map { it.timeSpan() ?: return null }
        versions += ownClientRecordVersions(type, spans.minOf { it.start }, spans.maxOf { it.endInclusive })
    }
    return versions
}

private val Record.clientId: String
    get() = requireNotNull(metadata.clientRecordId) { "an imported record always has a clientRecordId" }

/**
 * The time a record covers, for the types this app writes (the library keeps its own
 * instant-or-interval interfaces internal). Null for any other type: `ImportedRecordWritesTest`
 * checks every type in `HealthDataCatalog` has one.
 */
internal fun Record.timeSpan(): ClosedRange<Instant>? = when (this) {
    is ActiveCaloriesBurnedRecord -> startTime..endTime
    is CyclingPedalingCadenceRecord -> startTime..endTime
    is DistanceRecord -> startTime..endTime
    is ElevationGainedRecord -> startTime..endTime
    is ExerciseSessionRecord -> startTime..endTime
    is FloorsClimbedRecord -> startTime..endTime
    is HeartRateRecord -> startTime..endTime
    is PowerRecord -> startTime..endTime
    is SleepSessionRecord -> startTime..endTime
    is SpeedRecord -> startTime..endTime
    is StepsCadenceRecord -> startTime..endTime
    is StepsRecord -> startTime..endTime
    is TotalCaloriesBurnedRecord -> startTime..endTime
    is SkinTemperatureRecord -> startTime..endTime
    is BasalMetabolicRateRecord -> time..time
    is BodyFatRecord -> time..time
    is BodyWaterMassRecord -> time..time
    is HeartRateVariabilityRmssdRecord -> time..time
    is HeightRecord -> time..time
    is LeanBodyMassRecord -> time..time
    is OxygenSaturationRecord -> time..time
    is RespiratoryRateRecord -> time..time
    is RestingHeartRateRecord -> time..time
    is Vo2MaxRecord -> time..time
    is WeightRecord -> time..time
    else -> null
}
