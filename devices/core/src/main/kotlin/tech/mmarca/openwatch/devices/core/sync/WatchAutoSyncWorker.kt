package tech.mmarca.openwatch.devices.core.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import tech.mmarca.openwatch.data.repository.contract.DeviceRegistry
import tech.mmarca.openwatch.devices.core.DeviceLog
import kotlinx.coroutines.withContext

/**
 * One scheduled sync of one watch, run by `WorkManager`. Not a foreground
 * worker: an unasked-for sync must not take the app's single foreground
 * slot. Goes through [DeviceSyncController], so a scheduled run and a
 * tapped one cannot collide.
 */
class WatchAutoSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val deviceId = inputData.getString(KeyDeviceId)
        if (deviceId.isNullOrEmpty()) return Result.failure()

        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            WatchAutoSyncWorkerEntryPoint::class.java,
        )
        val scheduler = entryPoint.watchAutoSyncScheduler()

        // The stored interval is the authority: work that outlived it stops here.
        if (!scheduler.interval(deviceId).isOn) {
            scheduler.cancel(deviceId)
            return Result.success()
        }

        val device = entryPoint.deviceRegistry().devices.firstOrNull { it.id == deviceId }
        if (device == null) {
            // The watch was removed while this run was queued.
            scheduler.cancel(deviceId)
            return Result.success()
        }
        // A watch switched off keeps its schedule; the runs stop.
        if (!device.enabled) return Result.success()

        // Null means another sync holds the radio. Retried on the backoff rather than waiting a
        // whole period: with several watches on one schedule, the loser would otherwise always lose.
        // In this coroutine, not the controller's: the sync is the job's work, held awake by
        // the job and cancelled with it when the system stops the job.
        // The budget ends a long sync cleanly before the system's ten-minute limit would cancel it.
        val result = withContext(SyncBudget.forNext(BudgetMillis)) {
            entryPoint.deviceSyncController().runSync(deviceId)
        } ?: return if (runAttemptCount < MaxAttempts) Result.retry() else Result.success()

        return when (result) {
            is DeviceSyncResult.Succeeded -> {
                val continuation = inputData.getInt(KeyContinuation, 0)
                if (result.moreRemains && continuation < MaxContinuations) {
                    scheduler.continueSoon(deviceId, continuation + 1)
                }
                Result.success()
            }

            is DeviceSyncResult.Failed -> {
                Log.log("run failed: ${result.failure::class.simpleName}")
                // Out of range, busy radio, recording: retry on the backoff, then wait for the next
                // period. A missing grant or a paused sync waits for the user, not for a retry.
                if (result.failure.transient && runAttemptCount < MaxAttempts) Result.retry() else Result.success()
            }
        }
    }

    companion object {
        private const val KeyDeviceId = "device_id"
        private const val KeyContinuation = "continuation"

        /** Two minutes short of WorkManager's ten, for the run to end and report. */
        private const val BudgetMillis = 8 * 60_000L

        /** Follow-up runs after one its budget cut short, before the period takes over. */
        private const val MaxContinuations = 3

        /** Attempts per period, the first included. */
        private const val MaxAttempts = 3

        private val Log = DeviceLog("DeviceAutoSync")

        fun inputData(deviceId: String, continuation: Int = 0): Data =
            Data.Builder().putString(KeyDeviceId, deviceId).putInt(KeyContinuation, continuation).build()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WatchAutoSyncWorkerEntryPoint {
    fun deviceSyncController(): DeviceSyncController

    fun deviceRegistry(): DeviceRegistry

    fun watchAutoSyncScheduler(): WatchAutoSyncScheduler
}
