package tech.mmarca.openwatch.data.repository.contract

import tech.mmarca.openwatch.domain.model.ActivityWriteRequest

/**
 * The activity writes a watch's FIT files need. OpenVitals' contract also
 * reads; OpenWatch shows no activity, so only the write half is here.
 */
interface ActivityRepository {

    fun activityWritePermissions(request: ActivityWriteRequest): Set<String>

    suspend fun hasActivityWritePermission(request: ActivityWriteRequest): Boolean

    suspend fun writeActivityEntry(request: ActivityWriteRequest): String

    /** Writes many activities in one Health Connect call. Atomic: all land or none. */
    suspend fun writeActivityEntries(requests: List<ActivityWriteRequest>): List<String>
}
