package tech.mmarca.openwatch.features.watches

import android.content.Context
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.devices.core.sync.AutoSyncInterval
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncPort
import tech.mmarca.openwatch.devices.wearos.WearOsLog

/**
 * Owns the automatic watch sync schedule: the stored per-watch interval and
 * the `WorkManager` periodic work. One unique work per watch, named by
 * device id, so a new interval replaces the schedule. [restoreAll] covers
 * what WorkManager does not, and keeps any live schedule.
 */
@Singleton
class WatchAutoSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val deviceRepository: BleDeviceRepository,
    private val syncPort: DeviceSyncPort,
) {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    private val prefs by lazy { context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE) }

    /** The stored choice. A value this build no longer offers reads as OFF, not another schedule. */
    fun interval(deviceId: String): AutoSyncInterval =
        AutoSyncInterval.fromMinutes(prefs.getInt(deviceId, 0))

    private fun storeInterval(deviceId: String, interval: AutoSyncInterval) {
        prefs.edit { putInt(deviceId, interval.minutes) }
    }

    /** Stores the choice and re-plans from now, so a shorter interval runs sooner. */
    fun setInterval(deviceId: String, interval: AutoSyncInterval) {
        storeInterval(deviceId, interval)
        if (interval.isOn) {
            enqueue(deviceId, interval, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE)
        } else {
            cancel(deviceId)
        }
    }

    /** Re-plans every paired watch from what is stored. Idempotent; KEEP leaves live schedules alone. */
    fun restoreAll() {
        // Runs from Application.onCreate. Not worth failing app start over.
        runCatching {
            deviceRepository.devices.forEach { device ->
                val interval = interval(device.id)
                if (interval.isOn && syncPort.canSync(device)) {
                    enqueue(device.id, interval, ExistingPeriodicWorkPolicy.KEEP)
                } else {
                    // A watch that stopped being syncable, or a schedule that outlived its preference.
                    cancel(device.id)
                }
            }
        }.onFailure { WearOsLog.log("[AUTOSYNC] could not restore the schedules: $it") }
    }

    /** Stops a watch's schedule without touching the stored interval. */
    fun cancel(deviceId: String) {
        workManager.cancelUniqueWork(uniqueWorkName(deviceId))
    }

    /** Removal: the schedule goes with the watch, and so does the choice. */
    fun forget(deviceId: String) {
        cancel(deviceId)
        storeInterval(deviceId, AutoSyncInterval.OFF)
    }

    private fun enqueue(
        deviceId: String,
        interval: AutoSyncInterval,
        policy: ExistingPeriodicWorkPolicy,
    ) {
        val request = PeriodicWorkRequestBuilder<WatchAutoSyncWorker>(
            interval.minutes.toLong(),
            TimeUnit.MINUTES,
        )
            .setInputData(WatchAutoSyncWorker.inputData(deviceId))
            // Battery only: the sync is Bluetooth, and the app has no internet permission.
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(uniqueWorkName(deviceId), policy, request)
    }

    companion object {
        /** A watch out of range is usually back within minutes. */
        private const val RETRY_BACKOFF_MINUTES = 10L

        /** Minutes between runs per device id; absent means OFF. */
        private const val PREFS_FILE = "watch_auto_sync"

        fun uniqueWorkName(deviceId: String): String = "watch-auto-sync-$deviceId"
    }
}
