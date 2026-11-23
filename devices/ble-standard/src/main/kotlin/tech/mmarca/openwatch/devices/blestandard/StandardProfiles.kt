package tech.mmarca.openwatch.devices.blestandard

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import tech.mmarca.openwatch.devices.core.ble.BleUuids
import tech.mmarca.openwatch.devices.core.measure.PushedMeasurement
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

    /** The kinds the family names; a weight scale is the built-in [BleDeviceKind.SCALE]. */
    val BLOOD_PRESSURE_MONITOR = BleDeviceKind("BLOOD_PRESSURE_MONITOR")
    val GLUCOSE_METER = BleDeviceKind("GLUCOSE_METER")

    /** The Heart Rate Service (0x180D) and its measurement (0x2A37): the app's own constants. */
    val HEART_RATE_SERVICE: UUID = BleUuids.HEART_RATE.serviceUuid
    val HEART_RATE_MEASUREMENT: UUID = BleUuids.HEART_RATE.measurementUuid

    /** The Battery Service (0x180F) and its level (0x2A19, a uint8 percentage). */
    val BATTERY_SERVICE: UUID = BleUuids.BATTERY_SERVICE
    val BATTERY_LEVEL: UUID = BleUuids.BATTERY_LEVEL

    /** The Blood Pressure Service and its measurement, indicated. */
    val BLOOD_PRESSURE_SERVICE: UUID = sigUuid(0x1810)
    val BLOOD_PRESSURE_MEASUREMENT: UUID = sigUuid(0x2A35)

    /** The Weight Scale Service and its measurement, indicated. */
    val WEIGHT_SCALE_SERVICE: UUID = sigUuid(0x181D)
    val WEIGHT_MEASUREMENT: UUID = sigUuid(0x2A9D)

    /** The Glucose Service, its measurement and context (notified), and its Record Access Control Point (indicated, written). */
    val GLUCOSE_SERVICE: UUID = sigUuid(0x1808)
    val GLUCOSE_MEASUREMENT: UUID = sigUuid(0x2A18)
    val GLUCOSE_CONTEXT: UUID = sigUuid(0x2A34)
    val RECORD_ACCESS_CONTROL_POINT: UUID = sigUuid(0x2A52)

    /** How a 16-bit SIG number reads as the 128-bit UUID an advertisement and a GATT table carry. */
    fun sigUuid(number: Int): UUID =
        UUID.fromString("0000" + number.toString(16).padStart(4, '0') + "-0000-1000-8000-00805f9b34fb")
}

/** A strap of this family: a sensor that streams its heart rate, as the registry has always called one. */
val BleSensorDevice.isStandardHeartRateStrap: Boolean
    get() = integration == StandardProfiles.INTEGRATION && kind == BleDeviceKind.SENSOR &&
        BleSensorCapability.HEART_RATE in capabilities

/**
 * The profiles whose devices hand over what they measured when they wake, each by the kind its
 * devices are registered as: the service, the measurement, and the key a reading is kept and
 * written under. The keys are stored (the sink, Health Connect's ids): do not change them.
 */
enum class PushProfile(val kind: BleDeviceKind, val service: UUID, val measurement: UUID, val keyPrefix: String) {
    BLOOD_PRESSURE(
        StandardProfiles.BLOOD_PRESSURE_MONITOR,
        StandardProfiles.BLOOD_PRESSURE_SERVICE,
        StandardProfiles.BLOOD_PRESSURE_MEASUREMENT,
        "bp_",
    ),
    WEIGHT(BleDeviceKind.SCALE, StandardProfiles.WEIGHT_SCALE_SERVICE, StandardProfiles.WEIGHT_MEASUREMENT, "weight_"),
    GLUCOSE(StandardProfiles.GLUCOSE_METER, StandardProfiles.GLUCOSE_SERVICE, StandardProfiles.GLUCOSE_MEASUREMENT, "glucose_"),
    ;

    /**
     * A cuff's or a scale's indicated [bytes], received [at], as the sink keeps them, with the user
     * the device named; null for a value that does not parse, and for a glucose record, which
     * comes with its context. The key is the device's own user and time stamp, so a reading
     * handed over again is the same one; without a stamp, the time it came.
     */
    fun pushed(bytes: ByteArray, at: Instant, zone: ZoneId): Pair<PushedMeasurement, Int?>? {
        val (stamp, user) = when (this) {
            BLOOD_PRESSURE -> BloodPressureMeasurement.parse(bytes)?.let { it.timeStamp to it.userId }
            WEIGHT -> WeightMeasurement.parse(bytes)?.let { it.timeStamp to it.userId }
            GLUCOSE -> null
        } ?: return null
        val value = ReceivedValue(at.toEpochMilli(), zone.rules.getOffset(at).totalSeconds, bytes)
        val key = keyPrefix + "${user ?: "u"}_" + (stamp?.stamp ?: "r${at.toEpochMilli()}")
        return PushedMeasurement(key, value.encode()) to user
    }

    companion object {
        /** The profile [device] is read through; null for a strap and for another family's device. */
        fun of(device: BleSensorDevice): PushProfile? =
            entries.firstOrNull { it.kind == device.kind }?.takeIf { device.integration == StandardProfiles.INTEGRATION }

        /** A glucose record's key: its sequence number and the meter's own base time. */
        fun glucoseKey(measurement: GlucoseMeasurement): String =
            GLUCOSE.keyPrefix + "${measurement.sequenceNumber}_${measurement.baseTime.stamp}"
    }
}
