package tech.mmarca.openwatch

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import tech.mmarca.openwatch.devices.garmin.GarminLocalData
import tech.mmarca.openwatch.devices.garmin.GarminMusicRelay
import tech.mmarca.openwatch.devices.garmin.GarminNavigationRelay
import tech.mmarca.openwatch.devices.garmin.GarminNotificationBridge
import tech.mmarca.openwatch.devices.xiaomi.XiaomiScaleListener
import tech.mmarca.openwatch.devices.wearos.WearOsPhoneActivity
import tech.mmarca.openwatch.features.watches.WatchAutoSyncScheduler

/**
 * What the app starts at every process start that the first screen does not wait for.
 *
 * The Application asks for this through `dagger.Lazy` and builds it on a background
 * thread, so the device graph is not pulled into `super.onCreate()` on the main thread.
 */
@Singleton
class AppStartupActors @Inject constructor(
    private val garminNotificationBridge: GarminNotificationBridge,
    private val garminNavigationRelay: GarminNavigationRelay,
    private val garminMusicRelay: GarminMusicRelay,
    private val watchAutoSyncScheduler: WatchAutoSyncScheduler,
    private val garminLocalData: GarminLocalData,
    private val xiaomiScaleListener: XiaomiScaleListener,
    private val linkPublisher: tech.mmarca.openwatch.link.OpenWatchLinkPublisher,
    private val phoneActivity: WearOsPhoneActivity,
) {
    fun start() {
        // Each on its own: this runs at every process start, a scale broadcast or a weather
        // broadcast included, so one actor that throws must not stop the others or crash the
        // process every time it starts.
        val actors = listOf<Pair<String, () -> Unit>>(
            // Companion mode must be re-armed on every start.
            "garmin notifications" to garminNotificationBridge::onAppStart,
            "garmin navigation" to garminNavigationRelay::start,
            "garmin music" to garminMusicRelay::start,
            // Re-plans the sync schedules after what WorkManager does not cover.
            "sync schedules" to watchAutoSyncScheduler::restoreAll,
            // Watch files and sleep minutes age out even when no watch syncs any more.
            "garmin pruning" to garminLocalData::pruneOnAppStart,
            // A reboot or Bluetooth going off drops the scan that hears the scale.
            "scale listener" to xiaomiScaleListener::onAppStart,
            // OpenLink clients hear about device and profile changes while they are bound.
            "openlink publisher" to linkPublisher::start,
            // Tells the watches when the phone is unlocked, so they end a sleep hold they started.
            "phone activity" to phoneActivity::start,
        )
        for ((name, start) in actors) {
            try {
                start()
            } catch (error: Exception) {
                // The JVM's Log stub throws in a unit test; a log line must not undo the catch.
                runCatching { Log.w(TAG, "start-up actor failed: $name", error) }
            }
        }
    }

    private companion object {
        const val TAG = "AppStartupActors"
    }
}
