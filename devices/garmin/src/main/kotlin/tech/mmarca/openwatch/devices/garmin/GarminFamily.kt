package tech.mmarca.openwatch.devices.garmin

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import tech.mmarca.openwatch.core.performance.runSuspendCatching
import tech.mmarca.openwatch.data.repository.contract.DeviceRegistry
import tech.mmarca.openwatch.devices.core.DeviceFamily
import tech.mmarca.openwatch.devices.core.DeviceFeature
import tech.mmarca.openwatch.devices.core.LiveReading
import tech.mmarca.openwatch.devices.core.LiveReadingKind
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration

/** Garmin watches and bike computers, driven over GFDI. */
@Singleton
class GarminFamily @Inject constructor(
    private val deviceRepository: DeviceRegistry,
    private val stateStore: GarminDeviceStateStore,
    private val onboard: OnboardGarminWatchUseCase,
    private val localData: GarminLocalData,
    private val realtime: GarminRealtimeStore,
    private val bridge: GarminNotificationBridge,
) : DeviceFamily {

    override val id: String = DeviceIntegration.GARMIN.storageName

    override fun owns(device: BleSensorDevice): Boolean = device.isGarmin

    override fun features(device: BleSensorDevice): Set<DeviceFeature> =
        setOf(DeviceFeature.SYNC, DeviceFeature.FIND, DeviceFeature.LIVE_HEART_RATE, DeviceFeature.LIVE_STEPS)

    /**
     * The watch's realtime heart rate and steps, and none of another watch's; collecting holds
     * the live link open for the session.
     */
    override fun liveReadings(device: BleSensorDevice): Flow<LiveReading> {
        val own = realtime.readings.filter { it.deviceId == device.id }
        val heartRate = own
            .distinctUntilChangedBy { it.heartRateAt }
            .map { reading -> reading.heartRateAt?.let { at -> reading.heartRateBpm?.let { LiveReading(LiveReadingKind.HEART_RATE, at.toEpochMilli(), it.toDouble()) } } }
            .filterNotNull()
        val steps = own
            .distinctUntilChangedBy { it.stepsAt }
            .map { reading -> reading.stepsAt?.let { at -> reading.steps?.let { LiveReading(LiveReadingKind.STEPS, at.toEpochMilli(), it.toDouble()) } } }
            .filterNotNull()
        return merge(heartRate, steps)
            .onStart { bridge.holdLiveForSession(device.id, true) }
            .onCompletion { bridge.holdLiveForSession(device.id, false) }
    }

    /** The last Garmin device: its file copies and sleep minutes go with it, and the history may too. */
    override fun offersHistoryDeletion(device: BleSensorDevice): Boolean =
        owns(device) && deviceRepository.devices.none { it.id != device.id && owns(it) }

    override suspend fun forget(device: BleSensorDevice, deleteHistory: Boolean) {
        stateStore.clear(device.id, device.address)
        runSuspendCatching { onboard.forget(device.address) }
        if (deviceRepository.devices.none(::owns)) {
            runSuspendCatching { localData.clearAfterLastWatchRemoved(deleteHistory) }
        }
    }
}
