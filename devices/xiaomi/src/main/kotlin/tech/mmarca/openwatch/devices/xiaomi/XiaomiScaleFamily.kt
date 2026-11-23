package tech.mmarca.openwatch.devices.xiaomi

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tech.mmarca.openwatch.data.repository.contract.DeviceRegistry
import tech.mmarca.openwatch.devices.core.DeviceFamily
import tech.mmarca.openwatch.devices.core.DeviceFeature
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration

/**
 * The S400 scale as a device family: one registry row, which [XiaomiScaleListener] keeps beside
 * the scale's own state. It syncs nothing and streams nothing; its link is up while the scan
 * that hears it runs.
 */
@Singleton
class XiaomiScaleFamily @Inject constructor(
    private val listener: XiaomiScaleListener,
    registry: DeviceRegistry,
) : DeviceFamily {

    override val id: String = DeviceIntegration.XIAOMI.storageName

    override fun owns(device: BleSensorDevice): Boolean = device.isXiaomiScale

    override fun features(device: BleSensorDevice): Set<DeviceFeature> = emptySet()

    override val connections: Flow<Map<String, Boolean>> =
        combine(registry.devicesFlow, listener.status) { devices, status ->
            devices.filter(::owns).associate { it.id to (status == ScaleListenerStatus.LISTENING) }
        }

    /** The row is gone already; the scan, the association and the key go with it. Weigh-ins stay. */
    override suspend fun forget(device: BleSensorDevice, deleteHistory: Boolean) {
        listener.release()
    }
}
