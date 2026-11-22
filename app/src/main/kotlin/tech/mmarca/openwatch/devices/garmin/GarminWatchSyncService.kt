package tech.mmarca.openwatch.devices.garmin

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import tech.mmarca.openwatch.core.fit.FitDecoder
import tech.mmarca.openwatch.core.fit.fitInstant
import tech.mmarca.openwatch.core.presentation.isPermissionFailure
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.devices.core.RadioLeaseBusyException
import tech.mmarca.openwatch.devices.core.RadioLeaseOwner
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncPort
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncProgress
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncResult
import tech.mmarca.openwatch.devices.core.sync.HealthSeries
import tech.mmarca.openwatch.devices.core.sync.SkipReason
import tech.mmarca.openwatch.devices.core.sync.SkippedSeries
import tech.mmarca.openwatch.devices.core.sync.SyncFailure
import tech.mmarca.openwatch.devices.core.sync.SyncTally
import tech.mmarca.openwatch.devices.core.sync.SyncTrigger
import tech.mmarca.openwatch.devices.core.withRadioLease
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.healthconnect.HealthConnectSyncDisabledException
import tech.mmarca.openwatch.healthconnect.isHealthConnectRateLimit
import kotlinx.coroutines.plus
import tech.mmarca.openwatch.core.performance.ApplicationScope
import tech.mmarca.openwatch.devices.core.RadioLeaseRegistry
import tech.mmarca.openwatch.core.performance.DefaultDispatcherProvider
import tech.mmarca.openwatch.core.performance.DispatcherProvider

/**
 * Drives one sync with a Garmin watch: take the radio lease, open the link,
 * run the GFDI session, import, record what was taken, stamp the device.
 * Everything below it is radio-free; everything above knows nothing of COBS.
 */
@Singleton
class GarminWatchSyncService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleDeviceRepository: BleDeviceRepository,
    private val stateStore: GarminDeviceStateStore,
    private val downloadImport: GarminDownloadImport,
    /** Keeps a copy of every download before the watch archives it. */
    private val fileStore: GarminFileStore,
    /** Everything that touches Bluetooth. A test hands over a fake and drives a whole sync. */
    private val radio: GarminRadio,
    @ApplicationScope appScope: CoroutineScope,
    private val radioLeases: RadioLeaseRegistry,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
) : DeviceSyncPort {

    private val syncMutex = Mutex()

    /** Outlives one sync: closing the sync screen must not cancel the chain rebuild it started. */
    private val rebuildScope = appScope + dispatchers.io

    init {
        // Idempotent, and a no-op outside debug builds.
        GarminLog.installLogcatSink()
    }

    /** A watch OR an Edge bike computer — both pull recorded FIT files over GFDI. */
    override fun canSync(device: BleSensorDevice): Boolean = device.isGarminGfdi

    /**
     * The held link's owner for [device], backed by this service's stores.
     * [onFullSyncNeeded] stays with the caller, which guards against overlapping syncs.
     */
    fun heldSyncOwner(device: BleSensorDevice, onFullSyncNeeded: () -> Unit): GarminHeldSyncOwner =
        object : GarminHeldSyncOwner {
            override fun alreadySyncedKeys(): Set<String> = stateStore.syncedFileKeys(device.id)

            override suspend fun keep(file: GarminDownloadedFile) {
                fileStore.save(file, now = Instant.now(), deviceId = device.id)
            }

            override fun imported(files: List<GarminDownloadedFile>) {
                if (files.isNotEmpty()) importAnnouncedFiles(device, files)
            }

            override fun needsFullSync() = onFullSyncNeeded()
        }

    private fun importAnnouncedFiles(
        device: BleSensorDevice,
        files: List<GarminDownloadedFile>,
    ) {
        rebuildScope.launch {
            try {
                downloadImport.import(device.id, files)
                bleDeviceRepository.markSynced(device.id, Instant.now())
                GarminLog.log("[GARMIN-COMPANION] imported ${files.size} filtered file(s)")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                GarminLog.log("[GARMIN-COMPANION] filtered import failed: $error")
            }
        }
    }

    override suspend fun sync(
        device: BleSensorDevice,
        trigger: SyncTrigger,
        onProgress: ((DeviceSyncProgress) -> Unit)?,
    ): DeviceSyncResult = syncMutex.withLock {
        syncSerially(device, if (trigger == SyncTrigger.REQUESTED) REQUESTED_LINGER else Duration.ZERO, onProgress)
    }

    private suspend fun syncSerially(
        device: BleSensorDevice,
        listenAfter: Duration,
        onProgress: ((DeviceSyncProgress) -> Unit)?,
    ): DeviceSyncResult {
        // Files a run saved but never imported: a crash, or an import that threw. The watch
        // has archived them, so this is their only way in. Before the pull, so their keys are
        // recorded before the listing is filtered and they are not fetched twice.
        val pendingWithheld = try {
            importPendingFiles(device)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // The write path is down. Pulling more now would only queue more behind it.
            GarminLog.log("[GARMIN-SYNC] pending import failed: $error")
            return failed(error)
        }

        val pull = try {
            radioLeases.withRadioLease(device.address, RadioLeaseOwner.SYNC) {
                radio.pull(device, listenAfter, onProgress)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: RadioLeaseBusyException) {
            GarminLog.log("[GARMIN-SYNC] radio busy: ${error.holder}")
            return DeviceSyncResult.Failed(SyncFailure.RadioBusy)
        } catch (error: Exception) {
            GarminLog.log("[GARMIN-SYNC] failed: $error")
            return failed(error)
        }

        val downloaded = pull.files
        var activities = GarminActivityImportResult()
        if (downloaded.isNotEmpty()) {
            try {
                // Records the keys after the import, so a run that died mid-import downloads again.
                activities = downloadImport.import(device.id, downloaded)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Reaching here means the write path itself is unavailable.
                GarminLog.log("[GARMIN-SYNC] import failed: $error")
                // Nothing reached Health Connect, so the next run must fetch again.
                return failed(error)
            }
        }

        val done = outcome(downloaded.size, activities, pendingWithheld)
        val incompleteReason = pull.incompleteReason
        if (incompleteReason != null) {
            // The reason is the link's own account, for the log; the screen says "part-way".
            GarminLog.log("[GARMIN-SYNC] interrupted after ${downloaded.size} file(s): $incompleteReason")
            return DeviceSyncResult.Failed(SyncFailure.Interrupted, partial = done.takeIf { downloaded.isNotEmpty() })
        }

        bleDeviceRepository.markSynced(device.id, Instant.now())
        // Saying "synced" here hid workouts that never reached Health Connect.
        if (activities.failedWrites.isNotEmpty()) return DeviceSyncResult.Failed(SyncFailure.NotSaved, partial = done)
        return done
    }

    /**
     * What a run wrote and left out, said the way every integration says it. A workout waiting
     * for a grant stays pending on the phone, so the next sync after the grant writes it.
     */
    private fun outcome(files: Int, activities: GarminActivityImportResult, pendingWithheld: Set<String>) =
        DeviceSyncResult.Succeeded(
            written = mapOf(SyncTally.FILES to files),
            skipped = buildSet {
                val wellness = pendingWithheld + activities.wellnessWithheldPermissions
                if (wellness.isNotEmpty()) add(SkippedSeries(HealthSeries.RECORDS, SkipReason.PERMISSION, wellness))
                if (activities.missingPermission.isNotEmpty()) {
                    add(SkippedSeries(HealthSeries.WORKOUTS, SkipReason.PERMISSION, activities.missingWritePermissions))
                }
                if (activities.withoutCaloriesForWantOfWeight > 0) {
                    add(SkippedSeries(HealthSeries.WORKOUT_ENERGY, SkipReason.NO_BODY_WEIGHT))
                }
            },
        )

    /**
     * Makes the watch at [address] alert and keeps the link open while it
     * does. Find is a toggle with a timeout; completing [cancelled] stops it.
     * Returns whether the watch accepted the request. Throws
     * [RadioLeaseBusyException] or [GarminGattClientException].
     */
    suspend fun findWatch(
        address: String,
        timeout: Duration = GarminFindMyWatch.defaultTimeout,
        cancelled: CompletableDeferred<Unit>? = null,
    ): Boolean = radioLeases.withRadioLease(address, RadioLeaseOwner.FIND) {
        radio.find(address, timeout, cancelled)
    }


    /**
     * Writes [bytes] to the watch as a new [type] file, on a link opened for
     * this one send. Never throws for a watch that is busy, away or unwilling:
     * the result says which.
     */
    suspend fun uploadFile(
        device: BleSensorDevice,
        type: GarminUploadFileType,
        bytes: ByteArray,
        onStage: (GarminSendStage) -> Unit = {},
    ): GarminSendFileResult {
        // The SYNC lease is shared with a sync, so the mutex is what keeps the two apart.
        if (!syncMutex.tryLock()) return GarminSendFileResult.SyncRunning
        return try {
            onStage(GarminSendStage.CONNECTING)
            withTimeoutOrNull(SEND_TIMEOUT) {
                radioLeases.withRadioLease(device.address, RadioLeaseOwner.SYNC) {
                    radio.upload(device, type, bytes, onStage)
                }
            } ?: GarminSendFileResult.NoAnswer
        } catch (error: CancellationException) {
            throw error
        } catch (error: RadioLeaseBusyException) {
            GarminSendFileResult.Busy(error.holder)
        } catch (error: Exception) {
            GarminLog.log("[GARMIN-SEND] could not reach the watch: $error")
            GarminSendFileResult.Unreachable
        } finally {
            syncMutex.unlock()
        }
    }




    /**
     * Imports what [GarminFileStore.pending] holds for [device]; returns the write permissions
     * whose records were left out. Throws when the write path is down.
     */
    private suspend fun importPendingFiles(device: BleSensorDevice): Set<String> {
        val pending = fileStore.pending(device.id)
        if (pending.isEmpty()) return emptySet()
        GarminLog.log("[GARMIN-SYNC] importing ${pending.size} file(s) an earlier run saved but never imported")
        return downloadImport.import(device.id, pending).wellnessWithheldPermissions
    }

    /** The cause as a type: a missing grant or a paused sync is not a link that failed. */
    private fun failed(error: Throwable): DeviceSyncResult.Failed = DeviceSyncResult.Failed(
        when {
            error.isPermissionFailure() -> SyncFailure.HealthConnectPermission
            error.isHealthConnectRateLimit() -> SyncFailure.RateLimited
            error is HealthConnectSyncDisabledException -> SyncFailure.SyncPaused
            error is GarminGattClientException -> SyncFailure.Unreachable
            else -> SyncFailure.Unknown(error)
        },
    )


    private companion object {
        /** A whole-send safety net: handover, handshake and a small file fit well inside. */
        val SEND_TIMEOUT = 45.seconds

        /**
         * How long a requested sync holds the link after the pull. The watch runs its own
         * errands (weather) seconds after the link comes up; a background one does not wait.
         */
        val REQUESTED_LINGER = 20.seconds

    }
}

/**
 * The earliest local day the downloaded files carry data for, or null when
 * the watch dated none of them. Pure, so it is testable without the service.
 */
fun garminEarliestAffectedDay(
    downloaded: List<GarminDownloadedFile>,
    zone: ZoneId = ZoneId.systemDefault(),
): LocalDate? = downloaded
    .mapNotNull(::garminFileDate)
    .minOrNull()
    ?.atZone(zone)
    ?.toLocalDate()

/** True when a wanted file cannot be dated at all, so no day is safe to keep. */
fun garminNeedsFullBodyEnergyInvalidation(downloaded: List<GarminDownloadedFile>): Boolean =
    downloaded.any { it.entry.type.wanted && garminFileDate(it) == null }

/** The listing's date, or the earliest FIT timestamp when the listing has none. */
fun garminFileDate(file: GarminDownloadedFile): Instant? =
    file.entry.fileDate ?: runCatching { garminFitFileDate(file.bytes) }.getOrNull()

/** The earliest timestamp across the chained FIT files in [bytes], or null when none carries one. */
fun garminFitFileDate(bytes: ByteArray): Instant? {
    var offset = 0
    var earliest: Long? = null
    while (offset < bytes.size && FitDecoder.isFitFileAt(bytes, offset)) {
        val file = FitDecoder.readFile(bytes, offset)
        file.messages.mapNotNull { it.timestamp }.minOrNull()?.let { timestamp ->
            earliest = earliest?.coerceAtMost(timestamp) ?: timestamp
        }
        if (file.nextOffset <= offset) break
        offset = file.nextOffset
    }
    return earliest?.let(::fitInstant)
}
