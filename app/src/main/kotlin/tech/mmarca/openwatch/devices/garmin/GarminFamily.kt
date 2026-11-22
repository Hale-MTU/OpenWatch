package tech.mmarca.openwatch.devices.garmin

import javax.inject.Inject
import javax.inject.Singleton
import tech.mmarca.openwatch.core.performance.runSuspendCatching
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.devices.core.DeviceFamily
import tech.mmarca.openwatch.devices.core.DeviceFeature
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration

/** Garmin watches and bike computers, driven over GFDI. */
@Singleton
class GarminFamily @Inject constructor(
    private val deviceRepository: BleDeviceRepository,
    private val stateStore: GarminDeviceStateStore,
    private val onboard: OnboardGarminWatchUseCase,
    private val localData: GarminLocalData,
) : DeviceFamily {

    override val id: String = DeviceIntegration.GARMIN.storageName

    override fun owns(device: BleSensorDevice): Boolean = device.isGarminGfdi

    override fun features(device: BleSensorDevice): Set<DeviceFeature> =
        setOf(DeviceFeature.SYNC, DeviceFeature.FIND, DeviceFeature.LIVE_HEART_RATE)

    /** The last Garmin device: its file copies and sleep minutes go with it, and the history may too. */
    override fun offersHistoryDeletion(device: BleSensorDevice): Boolean =
        owns(device) && deviceRepository.devices.none { it.id != device.id && owns(it) }

    override suspend fun forget(device: BleSensorDevice, deleteHistory: Boolean) {
        stateStore.clear(device.id)
        runSuspendCatching { onboard.forget(device.address) }
        if (deviceRepository.devices.none(::owns)) {
            runSuspendCatching { localData.clearAfterLastWatchRemoved(deleteHistory) }
        }
    }
}
