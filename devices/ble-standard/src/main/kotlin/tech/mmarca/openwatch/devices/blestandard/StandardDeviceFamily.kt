package tech.mmarca.openwatch.devices.blestandard

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import tech.mmarca.openwatch.core.performance.runSuspendCatching
import tech.mmarca.openwatch.data.repository.contract.DeviceRegistry
import tech.mmarca.openwatch.data.repository.contract.DeviceStateRepository
import tech.mmarca.openwatch.devices.core.DeviceFamily
import tech.mmarca.openwatch.devices.core.DeviceFeature
import tech.mmarca.openwatch.devices.core.DevicePairing
import tech.mmarca.openwatch.devices.core.DevicePairingResult
import tech.mmarca.openwatch.devices.core.LiveReading
import tech.mmarca.openwatch.devices.core.PairingFailure
import tech.mmarca.openwatch.devices.core.pairing.WatchBondResult
import tech.mmarca.openwatch.devices.core.pairing.WatchPairingPort
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleDiscoveredDevice
import tech.mmarca.openwatch.domain.model.BleSensorCapability
import tech.mmarca.openwatch.domain.model.BleSensorDevice

/**
 * The devices that speak a Bluetooth SIG standard health profile, as one family: a heart rate
 * strap, a blood pressure monitor, a weight scale and a glucose meter, each by the service it
 * advertises. All pair through the app's add flow.
 *
 * A strap needs no bond (the Heart Rate Profile asks for none) and no association, since it is
 * read only while someone collects its stream; its registry row is a sensor with the heart rate
 * capability, as the registry has always kept one. The others hand over what they measured when
 * they wake, so they are bonded (their profiles require it) and associated, which is how Android
 * tells the app one is in range ([MeasurementCollector]); one is read once at pairing, while it
 * is awake.
 */
@Singleton
class StandardDeviceFamily @Inject constructor(
    private val registry: DeviceRegistry,
    private val state: DeviceStateRepository,
    private val heartRate: HeartRateStream,
    private val pairingPort: WatchPairingPort,
    private val collector: MeasurementCollector,
) : DeviceFamily, DevicePairing {

    override val id: String = StandardProfiles.FAMILY_ID

    override fun owns(device: BleSensorDevice): Boolean = device.integration == StandardProfiles.INTEGRATION

    override fun features(device: BleSensorDevice): Set<DeviceFeature> =
        if (device.isStandardHeartRateStrap) setOf(DeviceFeature.LIVE_HEART_RATE) else emptySet()

    override fun liveReadings(device: BleSensorDevice): Flow<LiveReading> = heartRate.readings(device)

    override val connections: Flow<Map<String, Boolean>> = heartRate.connections

    override val pairing: DevicePairing = this

    override val scanServiceUuids: Set<String> =
        (listOf(StandardProfiles.HEART_RATE_SERVICE) + PushProfile.entries.map { it.service }).mapTo(linkedSetOf()) { it.toString() }

    /**
     * The kind a device's advertised services make it: a glucose meter, a blood pressure monitor
     * or a scale first, since a device that measures one of those may also stream a heart rate.
     */
    override fun claim(device: BleDiscoveredDevice): BleDeviceKind? {
        val advertised = device.advertisedServiceUuids
        return CLAIM_ORDER.firstOrNull { it.service.toString() in advertised }?.kind
            ?: BleDeviceKind.SENSOR.takeIf { StandardProfiles.HEART_RATE_SERVICE.toString() in advertised }
    }

    override suspend fun pair(device: BleDiscoveredDevice, displayName: String): DevicePairingResult {
        val kind = claim(device) ?: return DevicePairingResult.Failed(PairingFailure.UNREACHABLE)
        if (kind == BleDeviceKind.SENSOR) return DevicePairingResult.Paired(register(device, displayName, kind).id)
        val bond = pairingPort.bond(device.address)
        when (bond) {
            WatchBondResult.REFUSED -> return DevicePairingResult.Failed(PairingFailure.BOND_REFUSED)
            WatchBondResult.UNREACHABLE -> return DevicePairingResult.Failed(PairingFailure.UNREACHABLE)
            WatchBondResult.ALREADY_BONDED, WatchBondResult.BONDED -> Unit
        }
        if (!pairingPort.associateCompanion(address = device.address, displayName = displayName)) {
            // Nothing is kept of a pairing that does not finish: the bond goes if this made it.
            if (bond == WatchBondResult.BONDED) runSuspendCatching { pairingPort.removeBond(device.address) }
            return DevicePairingResult.Failed(PairingFailure.NOT_ASSOCIATED)
        }
        val row = register(device, displayName, kind)
        // Awake now, and holding what it measured before it was paired.
        collector.collect(row)
        return DevicePairingResult.Paired(row.id)
    }

    /** The row is gone already: its stream, what it kept, and the association and bond the pairing made. Records in Health Connect stay. */
    override suspend fun forget(device: BleSensorDevice, deleteHistory: Boolean) {
        heartRate.forget(device.id)
        state.forget(device.id)
        if (PushProfile.of(device) != null) {
            runSuspendCatching { pairingPort.disassociateCompanion(device.address) }
            runSuspendCatching { pairingPort.removeBond(device.address) }
        }
    }

    private fun register(device: BleDiscoveredDevice, displayName: String, kind: BleDeviceKind): BleSensorDevice =
        registry.addDevice(
            displayName = displayName,
            address = device.address,
            bluetoothName = device.name,
            capabilities = if (kind == BleDeviceKind.SENSOR) setOf(BleSensorCapability.HEART_RATE) else emptySet(),
            kind = kind,
            integration = StandardProfiles.INTEGRATION,
        )

    private companion object {
        val CLAIM_ORDER = listOf(PushProfile.GLUCOSE, PushProfile.BLOOD_PRESSURE, PushProfile.WEIGHT)
    }
}
