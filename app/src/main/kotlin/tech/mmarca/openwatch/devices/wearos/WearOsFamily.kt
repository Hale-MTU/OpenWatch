package tech.mmarca.openwatch.devices.wearos

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import tech.mmarca.openwatch.core.performance.runSuspendCatching
import tech.mmarca.openwatch.devices.core.DeviceFamily
import tech.mmarca.openwatch.devices.core.DeviceFeature
import tech.mmarca.openwatch.devices.core.LiveReading
import tech.mmarca.openwatch.devices.core.LiveReadingKind
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration

/** Wear OS watches running the OpenWatch watch app, over the RFCOMM link. */
@Singleton
class WearOsFamily @Inject constructor(
    private val linkStore: WearOsLinkStore,
    private val onboard: OnboardWearOsWatchUseCase,
    private val companion: WearOsCompanionManager,
) : DeviceFamily {

    override val id: String = DeviceIntegration.WEAROS.storageName

    override fun owns(device: BleSensorDevice): Boolean = device.isWearosWatch

    override fun features(device: BleSensorDevice): Set<DeviceFeature> =
        setOf(DeviceFeature.SYNC, DeviceFeature.LIVE_HEART_RATE)

    /** The watch app's heart rate stream, stamped as it arrives. */
    override fun liveReadings(device: BleSensorDevice): Flow<LiveReading> =
        companion.liveHeartRate(device.address, device.bluetoothName ?: device.displayName)
            .filterNotNull()
            .map { bpm -> LiveReading(LiveReadingKind.HEART_RATE, System.currentTimeMillis(), bpm.toDouble()) }

    /** Up when the last check found the watch app running. */
    override val connections: Flow<Map<String, Boolean>> =
        linkStore.snapshots.map { snapshots -> snapshots.mapValues { (_, snapshot) -> snapshot.status == WearOsAppStatus.APP_RUNNING } }

    override suspend fun forget(device: BleSensorDevice, deleteHistory: Boolean) {
        linkStore.clear(device.id, device.address)
        runSuspendCatching { onboard.forget(device.address) }
    }
}
