package tech.mmarca.openwatch

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
        // Companion mode must be re-armed on every start.
        garminNotificationBridge.onAppStart()
        garminNavigationRelay.start()
        garminMusicRelay.start()
        // Re-plans the sync schedules after what WorkManager does not cover.
        watchAutoSyncScheduler.restoreAll()
        // Watch files and sleep minutes age out even when no watch syncs any more.
        garminLocalData.pruneOnAppStart()
        // A reboot or Bluetooth going off drops the scan that hears the scale.
        xiaomiScaleListener.onAppStart()
        // OpenLink clients hear about device and profile changes while they are bound.
        linkPublisher.start()
        // Tells the watches when the phone is unlocked, so they end a sleep hold they started.
        phoneActivity.start()
    }
}
