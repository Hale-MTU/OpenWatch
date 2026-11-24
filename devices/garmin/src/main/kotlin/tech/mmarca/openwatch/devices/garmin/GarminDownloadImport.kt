package tech.mmarca.openwatch.devices.garmin

import javax.inject.Inject
import javax.inject.Singleton
import tech.mmarca.openwatch.data.repository.contract.DeviceRegistry
import tech.mmarca.openwatch.devices.garmin.wellness.FitWellnessImporter

/**
 * Takes downloaded watch files into the app, then records them as done.
 *
 * The order is the point. The watch archives a file once it is downloaded, so
 * the recorded key is all that stops the next sync from fetching it again. A
 * key recorded before the import would hide a file the import never landed.
 */
@Singleton
class GarminDownloadImport @Inject constructor(
    private val importer: FitWellnessImporter,
    private val activityImporter: GarminActivityImporter,
    private val stateStore: GarminDeviceStateStore,
    private val fileStore: GarminFileStore,
    private val registry: DeviceRegistry,
) {
    /**
     * Throws when the write path is down. Nothing is recorded then, and the
     * saved copies stay pending for the next sync.
     */
    suspend fun import(deviceId: String, files: List<GarminDownloadedFile>): GarminActivityImportResult {
        // The watch's id salt keys its wellness records and rows apart from another Garmin's.
        val salt = registry.devices.firstOrNull { it.id == deviceId }?.idSalt.orEmpty()
        val wellnessWithheld = importer.import(files, salt)
        // Same path as a hand-picked FIT folder. Per-file failures come back in the result.
        val activities = activityImporter.import(files).copy(wellnessWithheldPermissions = wellnessWithheld)
        // Files with no stable key are fetched again every sync by design. A workout that did
        // not reach Health Connect keeps no key, and its saved copy stays pending: the watch
        // archived it on download and will not offer it again, so the copy is its only way in.
        val retry = activities.retry.toSet()
        stateStore.recordSyncedFileKeys(deviceId, (files - retry).mapNotNull { it.entry.dedupKey })
        fileStore.markImported(files - retry)
        return activities
    }
}
