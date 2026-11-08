package tech.mmarca.openwatch

import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import tech.mmarca.openwatch.devices.garmin.GarminLocalData
import tech.mmarca.openwatch.devices.garmin.GarminMusicRelay
import tech.mmarca.openwatch.devices.garmin.GarminNavigationRelay
import tech.mmarca.openwatch.devices.garmin.GarminNotificationBridge
import tech.mmarca.openwatch.devices.xiaomi.XiaomiScaleListener
import tech.mmarca.openwatch.features.watches.WatchAutoSyncScheduler
import tech.mmarca.openwatch.link.OpenWatchLinkPublisher

class AppStartupActorsTest {

    private val notificationBridge = mockk<GarminNotificationBridge>(relaxed = true)
    private val navigationRelay = mockk<GarminNavigationRelay>(relaxed = true)
    private val musicRelay = mockk<GarminMusicRelay>(relaxed = true)
    private val autoSyncScheduler = mockk<WatchAutoSyncScheduler>(relaxed = true)
    private val garminLocalData = mockk<GarminLocalData>(relaxed = true)
    private val scaleListener = mockk<XiaomiScaleListener>(relaxed = true)
    private val linkPublisher = mockk<OpenWatchLinkPublisher>(relaxed = true)

    @Test
    fun `every device actor is re-armed at start, and the OpenLink publisher starts`() {
        AppStartupActors(
            notificationBridge,
            navigationRelay,
            musicRelay,
            autoSyncScheduler,
            garminLocalData,
            scaleListener,
            linkPublisher,
        ).start()

        verify(exactly = 1) { notificationBridge.onAppStart() }
        verify(exactly = 1) { navigationRelay.start() }
        verify(exactly = 1) { musicRelay.start() }
        verify(exactly = 1) { autoSyncScheduler.restoreAll() }
        verify(exactly = 1) { garminLocalData.pruneOnAppStart() }
        verify(exactly = 1) { scaleListener.onAppStart() }
        verify(exactly = 1) { linkPublisher.start() }
    }
}
