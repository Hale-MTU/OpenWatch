package tech.mmarca.openwatch.data.repository.contract

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorCapability
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration

/**
 * The paired devices, as the device layer reads and changes them. Every read is answered from
 * memory (the first waits for the load) and every change takes effect at once. The app's
 * `BleDeviceRepository` implements it on the `devices` table; a device module takes this, never
 * the class.
 */
interface DeviceRegistry {

    /** The devices, from the first load on. */
    val devicesFlow: Flow<List<BleSensorDevice>>

    val devices: List<BleSensorDevice>

    /**
     * Registers [address], or folds these fields into the existing entry. The Sensors screen
     * owns [capabilities], watch onboarding owns [kind] and [integration]; each update leaves
     * the other half alone.
     */
    fun addDevice(
        displayName: String,
        address: String,
        bluetoothName: String?,
        capabilities: Set<BleSensorCapability>,
        wheelCircumferenceMm: Int? = null,
        kind: BleDeviceKind? = null,
        integration: DeviceIntegration? = null,
    ): BleSensorDevice

    fun updateDevice(
        deviceId: String,
        displayName: String? = null,
        /** The advertised name, which a re-add refreshes; null keeps the stored one. */
        bluetoothName: String? = null,
        capabilities: Set<BleSensorCapability>? = null,
        enabled: Boolean? = null,
        wheelCircumferenceMm: Int? = null,
        kind: BleDeviceKind? = null,
        integration: DeviceIntegration? = null,
    ): BleSensorDevice

    fun removeDevice(deviceId: String)

    /** Keeps a changed battery reading, stamped with when it moved. */
    fun updateBatteryLevel(deviceId: String, batteryPercent: Int)

    /** Stamps a device's last completed sync. A no-op for an unknown id. */
    fun markSynced(deviceId: String, at: Instant)
}
