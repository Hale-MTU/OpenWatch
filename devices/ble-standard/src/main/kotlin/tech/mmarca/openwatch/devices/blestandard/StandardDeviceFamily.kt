package tech.mmarca.openwatch.devices.blestandard

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import tech.mmarca.openwatch.data.repository.contract.DeviceRegistry
import tech.mmarca.openwatch.data.repository.contract.DeviceStateRepository
import tech.mmarca.openwatch.devices.core.DeviceFamily
import tech.mmarca.openwatch.devices.core.DeviceFeature
import tech.mmarca.openwatch.devices.core.DevicePairing
import tech.mmarca.openwatch.devices.core.DevicePairingResult
import tech.mmarca.openwatch.devices.core.LiveReading
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleDiscoveredDevice
import tech.mmarca.openwatch.domain.model.BleSensorCapability
import tech.mmarca.openwatch.domain.model.BleSensorDevice

/**
 * The devices that speak a Bluetooth SIG standard health profile, as one family: a heart rate
 * strap. It pairs through the app's add flow: a strap needs no bond (the Heart Rate Profile asks
 * for none) and no association, since it is read only while someone collects its stream; its
 * registry row is a sensor with the heart rate capability, as the registry has always kept one.
 */
@Singleton
class StandardDeviceFamily @Inject constructor(
    private val registry: DeviceRegistry,
    private val state: DeviceStateRepository,
    private val heartRate: HeartRateStream,
) : DeviceFamily, DevicePairing {

    override val id: String = StandardProfiles.FAMILY_ID

    override fun owns(device: BleSensorDevice): Boolean = device.integration == StandardProfiles.INTEGRATION

    override fun features(device: BleSensorDevice): Set<DeviceFeature> =
        if (device.isStandardHeartRateStrap) setOf(DeviceFeature.LIVE_HEART_RATE) else emptySet()

    override fun liveReadings(device: BleSensorDevice): Flow<LiveReading> = heartRate.readings(device)

    override val connections: Flow<Map<String, Boolean>> = heartRate.connections

    override val pairing: DevicePairing = this

    override val scanServiceUuids: Set<String> = setOf(StandardProfiles.HEART_RATE_SERVICE.toString())

    /** A device that advertises the Heart Rate Service is a strap. */
    override fun claim(device: BleDiscoveredDevice): BleDeviceKind? =
        BleDeviceKind.SENSOR.takeIf { StandardProfiles.HEART_RATE_SERVICE.toString() in device.advertisedServiceUuids }

    override suspend fun pair(device: BleDiscoveredDevice, displayName: String): DevicePairingResult {
        val row = registry.addDevice(
            displayName = displayName,
            address = device.address,
            bluetoothName = device.name,
            capabilities = setOf(BleSensorCapability.HEART_RATE),
            kind = BleDeviceKind.SENSOR,
            integration = StandardProfiles.INTEGRATION,
        )
        return DevicePairingResult.Paired(row.id)
    }

    /** The row is gone already: its stream and what it kept go with it. Records in Health Connect stay. */
    override suspend fun forget(device: BleSensorDevice, deleteHistory: Boolean) {
        heartRate.forget(device.id)
        state.forget(device.id)
    }
}
