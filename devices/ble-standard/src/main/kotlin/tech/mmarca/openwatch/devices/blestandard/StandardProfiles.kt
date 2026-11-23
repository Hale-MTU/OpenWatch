package tech.mmarca.openwatch.devices.blestandard

import java.util.UUID
import tech.mmarca.openwatch.devices.core.ble.BleUuids
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorCapability
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration

/**
 * The family's names and the profiles' UUIDs. The family's id, its kinds and its record ids are
 * stored (the registry, Health Connect): do not rename them.
 */
object StandardProfiles {

    /** The family's persisted name: its devices' `integration`, its key in the `DeviceFamily` map. */
    const val FAMILY_ID = "BLE_STANDARD"

    val INTEGRATION = DeviceIntegration(FAMILY_ID)

    /** What every record id of the family starts with, before the measurement's own key. */
    const val RECORD_ID_PREFIX = "ble_standard_"

    /** The Heart Rate Service (0x180D) and its measurement (0x2A37): the app's own constants. */
    val HEART_RATE_SERVICE: UUID = BleUuids.HEART_RATE.serviceUuid
    val HEART_RATE_MEASUREMENT: UUID = BleUuids.HEART_RATE.measurementUuid

    /** The Battery Service (0x180F) and its level (0x2A19, a uint8 percentage). */
    val BATTERY_SERVICE: UUID = BleUuids.BATTERY_SERVICE
    val BATTERY_LEVEL: UUID = BleUuids.BATTERY_LEVEL
}

/** A strap of this family: a sensor that streams its heart rate, as the registry has always called one. */
val BleSensorDevice.isStandardHeartRateStrap: Boolean
    get() = integration == StandardProfiles.INTEGRATION && kind == BleDeviceKind.SENSOR &&
        BleSensorCapability.HEART_RATE in capabilities
