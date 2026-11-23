package tech.mmarca.openwatch.data.repository

import androidx.health.connect.client.records.Record
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass
import kotlinx.coroutines.withContext
import tech.mmarca.openwatch.core.performance.DispatcherProvider
import tech.mmarca.openwatch.healthconnect.HealthConnectGateway

/**
 * The one door every device write goes through, so a deterministic
 * `clientRecordId` upserts the same way for a watch, a scale and a re-sync.
 */
@Singleton
class ImportedRecordRepository @Inject constructor(
    private val hc: HealthConnectGateway,
    private val dispatchers: DispatcherProvider,
) : HealthRecordStore {
    override suspend fun insertImportedRecords(records: List<Record>) =
        withContext(dispatchers.io) {
            hc.insertImportedRecords(records)
        }

    override suspend fun deleteImportedRecordsByClientIds(recordType: KClass<out Record>, clientRecordIds: List<String>) =
        withContext(dispatchers.io) {
            hc.deleteImportedRecordsByClientIds(recordType, clientRecordIds)
        }

    override suspend fun ownClientRecordVersions(
        recordType: KClass<out Record>,
        start: Instant,
        end: Instant,
    ): Map<String, Long> =
        withContext(dispatchers.io) {
            hc.readOwnClientRecordVersions(recordType, start, end)
        }
}
