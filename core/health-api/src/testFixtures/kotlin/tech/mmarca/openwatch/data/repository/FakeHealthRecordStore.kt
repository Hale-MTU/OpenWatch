package tech.mmarca.openwatch.data.repository

import androidx.health.connect.client.records.Record
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Health Connect's writes in memory, keyed by `clientRecordId`. [Provider.PLATFORM] behaves as
 * measured on Android 17 (`ClientRecordVersionTest`): an equal or higher version replaces the
 * stored record, a lower one is kept out, nothing throws. [Provider.REFUSING_APK] refuses a whole
 * batch that holds any stored id, as the APK on older Android is taken to; with
 * [refusesNewerVersions] off it lets a newer version through.
 */
class FakeHealthRecordStore(
    var provider: Provider = Provider.PLATFORM,
    var refusesNewerVersions: Boolean = true,
) : HealthRecordStore {

    enum class Provider { PLATFORM, REFUSING_APK }

    val stored = linkedMapOf<String, Record>()

    /** Every insert, refused or not, as the ids it held. */
    val inserts = mutableListOf<List<String>>()
    var reads = 0
        private set

    /** A failure to throw before anything else, for the batch it is given. */
    var failure: (List<Record>) -> Exception? = { null }

    override suspend fun insertImportedRecords(records: List<Record>) {
        inserts += records.map { it.id }
        failure(records)?.let { throw it }
        if (provider == Provider.REFUSING_APK && records.any { refuses(it) }) {
            throw IllegalStateException("refused")
        }
        for (record in records) {
            val old = stored[record.id]
            if (old == null || record.version >= old.version) stored[record.id] = record
        }
    }

    override suspend fun deleteImportedRecordsByClientIds(recordType: KClass<out Record>, clientRecordIds: List<String>) {
        for (id in clientRecordIds) if (stored[id]?.let(recordType::isInstance) == true) stored.remove(id)
    }

    override suspend fun ownClientRecordVersions(
        recordType: KClass<out Record>,
        start: Instant,
        end: Instant,
    ): Map<String, Long> {
        reads++
        return stored.values
            .filter { recordType.isInstance(it) && it.timeSpan()!!.let { span -> span.start <= end && span.endInclusive >= start } }
            .associate { it.id to it.version }
    }

    private fun refuses(record: Record): Boolean {
        val old = stored[record.id] ?: return false
        return refusesNewerVersions || record.version <= old.version
    }

    private val Record.id: String get() = metadata.clientRecordId!!
    private val Record.version: Long get() = metadata.clientRecordVersion
}
