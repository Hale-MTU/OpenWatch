package tech.mmarca.openwatch.devices.core.sync

import javax.inject.Inject
import javax.inject.Singleton
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
import tech.mmarca.openwatch.core.performance.ApplicationScope

/** Where a device sync has got to, for the row that started it. Device-agnostic. */
data class DeviceSyncUiState(
    /** The device id being synced, or null. Scoped so only one row shows a spinner. */
    val syncingDeviceId: String? = null,
    /** The running sync's last tick; null when none runs. */
    val progress: DeviceSyncProgress? = null,
    /** The device the last completed run was for, so another watch's card does not show its outcome. */
    val lastDeviceId: String? = null,
    /** What the last completed run wrote and left out; for a failed one, what it wrote first. */
    val last: DeviceSyncResult.Succeeded? = null,
    /** Why the last requested run failed; a background one ends without it. */
    val failure: SyncFailure? = null,
) {
    val isSyncing: Boolean get() = syncingDeviceId != null

    fun isSyncingDevice(deviceId: String): Boolean = syncingDeviceId == deviceId

    /** The last run's outcome for [deviceId]; none while a sync runs. Only this session's: nothing persists it. */
    fun lastFor(deviceId: String): DeviceSyncResult.Succeeded? = last.takeIf { !isSyncing && lastDeviceId == deviceId }

    /** Why the last run for [deviceId] failed; none while a sync runs. */
    fun failureFor(deviceId: String): SyncFailure? = failure.takeIf { !isSyncing && lastDeviceId == deviceId }
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
    private val wakeLock: SyncWakeLock = SyncWakeLock.None,
) {

    private val _state = MutableStateFlow(DeviceSyncUiState())
    val state: StateFlow<DeviceSyncUiState> = _state.asStateFlow()

    /**
     * Syncs [deviceId], one at a time, on the controller's own scope, and returns at once:
     * for a screen or another app over OpenLink, which must not wait. A device no port claims,
     * or a radio already busy, is null. A [SyncTrigger.BACKGROUND] failure ends idle, with no banner.
     */
    fun syncDevice(
        deviceId: String,
        trigger: SyncTrigger = SyncTrigger.REQUESTED,
    ): Deferred<DeviceSyncResult>? {
        val device = claim(deviceId) ?: return null
        // No job holds the phone awake for this one; the wake lock does.
        return scope.async { wakeLock.holding { runClaimed(device, trigger) } }
    }

    /**
     * Syncs [deviceId] in the caller's own coroutine: for the scheduled worker, so the sync
     * is the job's work. The job's wake lock then covers it, and when the system stops the
     * job, the sync is cancelled with it rather than left running unowned. Every integration
     * resumes from what it saved. Null as for [syncDevice].
     */
    suspend fun runSync(deviceId: String, trigger: SyncTrigger = SyncTrigger.BACKGROUND): DeviceSyncResult? {
        val device = claim(deviceId) ?: return null
        return runClaimed(device, trigger)
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
        val claimed = DeviceSyncUiState(syncingDeviceId = deviceId, progress = DeviceSyncProgress(DeviceSyncPhase.HANDSHAKE))
        while (true) {
            val current = _state.value
            if (current.isSyncing) return null
            if (_state.compareAndSet(current, claimed)) return device
        }
    }

    private suspend fun runClaimed(device: BleSensorDevice, trigger: SyncTrigger): DeviceSyncResult {
        val deviceId = device.id
        // The port owns the sequence. The catch is defensive: an uncaught exception would
        // leave the radio reading busy forever.
        val result = try {
            syncPort.sync(
                device,
                trigger = trigger,
                onProgress = { progress ->
                    _state.update { current ->
                        if (current.syncingDeviceId != deviceId) current else current.copy(progress = progress)
                    }
                },
            )
        } catch (error: CancellationException) {
            _state.value = DeviceSyncUiState()
            throw error
        } catch (error: Exception) {
            DeviceSyncResult.Failed(SyncFailure.Unknown(error))
        }
        _state.value = when (result) {
            is DeviceSyncResult.Succeeded -> DeviceSyncUiState(lastDeviceId = deviceId, last = result)

            is DeviceSyncResult.Failed -> if (trigger == SyncTrigger.BACKGROUND) {
                DeviceSyncUiState()
            } else {
                DeviceSyncUiState(lastDeviceId = deviceId, last = result.partial, failure = result.failure)
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
