package tech.mmarca.openwatch.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import kotlin.reflect.KClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Page-streamed read of every record of a type in a range. Streamed, not
 * accumulated: a year of samples pinned small heaps at their limit. A page
 * failure throws rather than truncating.
 */
internal class SyncRecordsReader(private val client: () -> HealthConnectClient) {

    @Suppress("UNCHECKED_CAST")
    suspend fun forEachRecordPage(
        recordType: KClass<out Record>,
        start: Instant,
        end: Instant,
        action: suspend (List<Record>) -> Unit,
    ) {
        val filter = TimeRangeFilter.between(start, end)
        var pageToken: String? = null
        do {
            val response = withContext(Dispatchers.IO) {
                client().readRecords(
                    ReadRecordsRequest(
                        recordType = recordType as KClass<Record>,
                        timeRangeFilter = filter,
                        pageSize = SYNC_READ_PAGE_SIZE,
                        pageToken = pageToken,
                    ),
                )
            }
            if (response.records.isNotEmpty()) action(response.records)
            pageToken = response.pageToken
        } while (pageToken != null)
    }

    private companion object {
        const val SYNC_READ_PAGE_SIZE = 1000
    }
}
