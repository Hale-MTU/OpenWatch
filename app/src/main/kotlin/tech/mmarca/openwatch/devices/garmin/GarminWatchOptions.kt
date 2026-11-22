package tech.mmarca.openwatch.devices.garmin

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tech.mmarca.openwatch.comaps.CoMapsGuidanceFeed
import tech.mmarca.openwatch.comaps.CoMapsNavigationRepository
import tech.mmarca.openwatch.data.repository.BleDeviceRepository

/** A Garmin watch's switches and what it declared, as its device screen shows them. */
data class GarminWatchOptionValues(
    /** What the watch declared in its last handshake. Empty means unknown, not none. */
    val capabilities: Set<GarminCapability>,
    val stayConnected: Boolean,
    val liveReadings: Boolean,
    val musicControls: Boolean,
    val calendarSync: Boolean,
    val navigationOnWatch: Boolean,
    /** Keyed by the watch's address, as the store keeps it; on for a watch not registered. */
    val highMtu: Boolean,
)

/**
 * The Garmin watch's switches for its device screen. [values] reads the store again after each
 * change made through it, whoever made it, and each setter goes to the part of the device layer
 * that owns the switch: the bridge for the held link and what rides it, the relays for music and
 * guidance, the store for the calendar and the packet size. The grants the switches need are
 * asked here too, so the screen names no Android source.
 */
@Singleton
class GarminWatchOptions @Inject constructor(
    private val deviceRepository: BleDeviceRepository,
    private val stateStore: GarminDeviceStateStore,
    private val bridge: GarminNotificationBridge,
    private val navigationRelay: GarminNavigationRelay,
    private val musicRelay: GarminMusicRelay,
    private val calendarSource: GarminCalendarSource,
    private val coMaps: CoMapsNavigationRepository,
    private val guidanceFeed: CoMapsGuidanceFeed,
    private val realtime: GarminRealtimeStore,
) {

    /** [deviceId]'s switches, now and after each change to the store or the registry. */
    fun values(deviceId: String): Flow<GarminWatchOptionValues> =
        combine(deviceRepository.devicesFlow, stateStore.changes) { devices, _ ->
            val device = devices.firstOrNull { it.id == deviceId }
            GarminWatchOptionValues(
                capabilities = if (device?.isGarmin == true) stateStore.capabilities(deviceId) else emptySet(),
                stayConnected = stateStore.stayConnected(deviceId),
                liveReadings = stateStore.liveReadings(deviceId),
                musicControls = stateStore.musicControls(deviceId),
                calendarSync = stateStore.calendarSync(deviceId),
                navigationOnWatch = stateStore.navigationOnWatch(deviceId),
                highMtu = device?.let { stateStore.highMtu(it.address) } ?: true,
            )
        }.distinctUntilChanged()

    /** The most recent live heart rate from [deviceId], while one is arriving: the held link may be another watch's. */
    fun liveHeartRate(deviceId: String): Flow<Int?> =
        realtime.readings.map { live -> live.takeIf { it.deviceId == deviceId }?.freshHeartRate() }.distinctUntilChanged()

    /** Companion mode. The bridge owns the switch, presence observation and the held link together. */
    fun setStayConnected(deviceId: String, enabled: Boolean) {
        bridge.onStayConnectedChanged(deviceId, enabled)
    }

    fun setLiveReadings(deviceId: String, enabled: Boolean) {
        bridge.onLiveReadingsChanged(deviceId, enabled)
    }

    fun setMusicControls(deviceId: String, enabled: Boolean) {
        bridge.onMusicControlsChanged(deviceId, enabled)
    }

    fun setCalendarSync(deviceId: String, enabled: Boolean) {
        stateStore.setCalendarSync(deviceId, enabled)
    }

    fun setNavigationOnWatch(deviceId: String, enabled: Boolean) {
        navigationRelay.onEnabledChanged(deviceId, enabled)
    }

    /** Whether links ask the watch for large packets. Keyed by the watch's address, as the store is. */
    fun setHighMtu(deviceId: String, enabled: Boolean) {
        val address = deviceRepository.devices.firstOrNull { it.id == deviceId }?.address ?: return
        stateStore.setHighMtu(address, enabled)
    }

    fun hasCalendarPermission(): Boolean = calendarSource.hasPermission()

    fun hasCoMapsPermission(): Boolean = coMaps.hasPermission()

    /** CoMaps' own permission, named after the installed flavour; null without a CoMaps installed. */
    fun coMapsPermissionName(): String? = coMaps.permissionName()

    /** After CoMaps' dialog: the grant is probed again and the feed re-opened, since a refused observer stays refused. */
    fun onCoMapsPermissionAnswered() {
        guidanceFeed.refresh()
    }

    /** Tries the phone's players again, on the way back from Android's notification-access settings. */
    fun refreshMusicAccess() {
        musicRelay.refreshAccess()
    }
}
