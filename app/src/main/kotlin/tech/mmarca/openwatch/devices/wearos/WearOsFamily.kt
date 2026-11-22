package tech.mmarca.openwatch.devices.wearos

import javax.inject.Inject
import javax.inject.Singleton
import tech.mmarca.openwatch.core.performance.runSuspendCatching
import tech.mmarca.openwatch.devices.core.DeviceFamily
import tech.mmarca.openwatch.devices.core.DeviceFeature
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration

/** Wear OS watches running the OpenWatch watch app, over the RFCOMM link. */
@Singleton
class WearOsFamily @Inject constructor(
    private val linkStore: WearOsLinkStore,
    private val onboard: OnboardWearOsWatchUseCase,
) : DeviceFamily {

    override val id: String = DeviceIntegration.WEAROS.storageName

    override fun owns(device: BleSensorDevice): Boolean = device.isWearosWatch

    override fun features(device: BleSensorDevice): Set<DeviceFeature> =
        setOf(DeviceFeature.SYNC, DeviceFeature.LIVE_HEART_RATE)

    override suspend fun forget(device: BleSensorDevice, deleteHistory: Boolean) {
        linkStore.clear(device.id, device.address)
        runSuspendCatching { onboard.forget(device.address) }
    }
}
