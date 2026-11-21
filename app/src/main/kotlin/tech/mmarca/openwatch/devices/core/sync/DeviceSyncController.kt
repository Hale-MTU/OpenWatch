package tech.mmarca.openwatch.devices.core.sync

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.core.presentation.ScreenError
import tech.mmarca.openwatch.core.performance.ApplicationScope

/** Where a device sync has got to, for the row that started it. Device-agnostic. */
data class DeviceSyncUiState(
    /** The device id being synced, or null. Scoped so only one row shows a spinner. */
    val syncingDeviceId: String? = null,
    val phase: DeviceSyncPhase? = null,
    val filesTotal: Int = 0,
    val filesDone: Int = 0,
    /** The device the last completed run synced, so a second watch's card does not show its outcome. */
    val lastDeviceId: String? = null,
    /** Files downloaded and handed to the importer by the last completed run. */
    val lastFileCount: Int? = null,
    /** Estimated nights of sleep written by the last completed run. */
    val lastNightCount: Int? = null,
    /** Minutes of steps written by the last completed run. */
    val lastStepMinuteCount: Int? = null,
    /** The last completed run skipped an estimate for want of the body weight. */
    val lastMissingBodyWeight: Boolean = false,
    /** With [lastMissingBodyWeight]: Health Connect's weight was unreadable for want of the grant. */
    val lastWeightReadWithheld: Boolean = false,
    /** The series the last completed run left out for want of a grant. */
    val lastUnwritten: Set<UnwrittenSeries> = emptySet(),
    /** Health Connect grants the last completed run skipped a write for want of. */
    val lastWithheldPermissions: Set<String> = emptySet(),
    val error: ScreenError? = null,
) {
    val isSyncing: Boolean get() = syncingDeviceId != null

    fun isSyncingDevice(deviceId: String): Boolean = syncingDeviceId == deviceId
}

/**
 * Runs a device sync through the integration that owns the device. A
 * singleton with its own scope: the sync outlives the screen, and every
 * watch surface reads the same state.
 */
@Singleton
class DeviceSyncController @Inject constructor(
    private val deviceRepository: BleDeviceRepository,
    private val syncPort: DeviceSyncPort,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(DeviceSyncUiState())
    val state: StateFlow<DeviceSyncUiState> = _state.asStateFlow()

    companion object {
        /**
         * How long a manual sync lingers after the pull. The watch runs its
         * own errands (weather) seconds after the link comes up.
         */
        val MANUAL_SYNC_LINGER: Duration = 20.seconds
    }

    /**
     * Syncs [deviceId], one at a time, on the controller's own scope, and returns at once:
     * for a screen or another app over OpenLink, which must not wait. A device no port claims,
     * or a radio already busy, is null. [listenAfter] is the diagnostic window held open
     * after the sync. [silent] is for the automatic schedule: a failure ends idle, no banner.
     */
    fun syncDevice(
        deviceId: String,
        listenAfter: Duration = Duration.ZERO,
        silent: Boolean = false,
    ): Deferred<DeviceSyncResult>? {
        val device = claim(deviceId) ?: return null
        return scope.async { runClaimed(device, listenAfter, silent) }
    }

    /**
     * Syncs [deviceId] in the caller's own coroutine: for the scheduled worker, so the sync
     * is the job's work. The job's wake lock then covers it, and when the system stops the
     * job, the sync is cancelled with it rather than left running unowned. Every integration
     * resumes from what it saved. Null as for [syncDevice].
     */
    suspend fun runSync(deviceId: String, silent: Boolean = true): DeviceSyncResult? {
        val device = claim(deviceId) ?: return null
        return runClaimed(device, Duration.ZERO, silent)
    }

    /**
     * Takes the radio for [deviceId]; null when no port claims it or a sync already runs.
     * Claimed before any launch, so the radio reads busy at once. A screen, the schedule and
     * another app over OpenLink call this from different threads: the claim is one
     * compare-and-set, so only one of two callers at the same moment gets the radio.
     */
    private fun claim(deviceId: String): BleSensorDevice? {
        val device = deviceRepository.devices.firstOrNull { it.id == deviceId } ?: return null
        if (!syncPort.canSync(device)) return null
        val claimed = DeviceSyncUiState(syncingDeviceId = deviceId, phase = DeviceSyncPhase.HANDSHAKE)
        while (true) {
            val current = _state.value
            if (current.isSyncing) return null
            if (_state.compareAndSet(current, claimed)) return device
        }
    }

    private suspend fun runClaimed(device: BleSensorDevice, listenAfter: Duration, silent: Boolean): DeviceSyncResult {
        val deviceId = device.id
        // The port owns the sequence. The catch is defensive: an uncaught exception would
        // leave the radio reading busy forever.
        val result = try {
            syncPort.sync(
                device,
                listenAfter = listenAfter,
                onProgress = { progress ->
                    _state.update { current ->
                        if (current.syncingDeviceId != deviceId) {
                            current
                        } else {
                            current.copy(
                                phase = progress.phase,
                                filesTotal = progress.filesTotal,
                                filesDone = progress.filesDone,
                            )
                        }
                    }
                },
            )
        } catch (error: CancellationException) {
            _state.value = DeviceSyncUiState()
            throw error
        } catch (error: Exception) {
            DeviceSyncResult.Failed(error.message ?: error.toString())
        }
        _state.value = when (result) {
            is DeviceSyncResult.Succeeded -> DeviceSyncUiState(
                phase = DeviceSyncPhase.COMPLETE,
                lastDeviceId = deviceId,
                lastFileCount = result.fileCount,
                lastNightCount = result.nightCount,
                lastStepMinuteCount = result.stepMinuteCount,
                lastMissingBodyWeight = result.missingBodyWeight,
                lastWeightReadWithheld = result.weightReadWithheld,
                lastUnwritten = result.unwritten,
                lastWithheldPermissions = result.withheldPermissions,
            )

            is DeviceSyncResult.Failed -> if (silent) {
                DeviceSyncUiState()
            } else {
                DeviceSyncUiState(error = result.error)
            }
        }
        return result
    }

    /** Clears the finished/failed banner so the row goes back to normal. */
    fun clear() {
        if (_state.value.isSyncing) return
        _state.value = DeviceSyncUiState()
    }
}
