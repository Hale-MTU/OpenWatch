package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Pressure
import androidx.health.connect.client.units.Volume
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToLong
import kotlin.reflect.KClass
import tech.mmarca.openwatch.devices.core.saltedRecordId
import tech.mmarca.openwatch.wearlink.WearLinkEntry
import tech.mmarca.openwatch.wearlink.WearLinkEntry.Type

/**
 * Maps the entries made on the watch ([WearLinkEntry], `SINCE lg`) to Health
 * Connect: a glass of water, a weight, a blood pressure and a breathing
 * session the wearer logged, and a heart rate or HRV a spot measurement took,
 * each keyed `wearos_entry_<type>_<millis>` with the watch's id salt, so a
 * re-pull is the same record and is skipped as present. A value entered by
 * hand (`h`) is a manual entry, one a sensor measured (`m`) an actively
 * recorded one, both from a watch.
 *
 * Pure, so it is testable without a device.
 */
internal object WearOsEntryImport {

    const val CLIENT_ID_PREFIX = "wearos_entry_"

    /** The record type an entry of [type] becomes. */
    fun recordType(type: Type): KClass<out Record> = when (type) {
        Type.WATER -> HydrationRecord::class
        Type.WEIGHT -> WeightRecord::class
        Type.BLOOD_PRESSURE -> BloodPressureRecord::class
        Type.MINDFULNESS -> MindfulnessSessionRecord::class
        Type.HEART_RATE -> HeartRateRecord::class
        Type.HRV -> HeartRateVariabilityRmssdRecord::class
    }

    /** The write grant an entry of [type] needs. */
    fun writePermission(type: Type): String = HealthPermission.getWritePermission(recordType(type))

    /**
     * Whether this phone's Health Connect can keep an entry of [type] at all: a breathing session
     * only where it stores mindfulness sessions ([mindfulness]). One it cannot keep is dropped,
     * not held back: no grant would ever let it in.
     */
    fun isStorable(type: Type, mindfulness: Boolean): Boolean = type != Type.MINDFULNESS || mindfulness

    /**
     * The record of [entry], or null when Health Connect would refuse the value. A null is
     * dropped, never retried: the same entry would be refused again.
     */
    fun record(entry: WearLinkEntry, zone: ZoneId, salt: String = ""): Record? = try {
        val at = Instant.ofEpochMilli(entry.epochMillis)
        val offset = zone.rules.getOffset(at)
        val metadata = metadata(entry, salt)
        when (entry.type) {
            Type.WATER -> HydrationRecord(
                startTime = at, startZoneOffset = offset, endTime = at.plusSeconds(1), endZoneOffset = offset,
                volume = Volume.milliliters(entry.value), metadata = metadata,
            )
            Type.WEIGHT -> WeightRecord(time = at, zoneOffset = offset, weight = Mass.kilograms(entry.value), metadata = metadata)
            Type.BLOOD_PRESSURE -> BloodPressureRecord(
                time = at, zoneOffset = offset,
                systolic = Pressure.millimetersOfMercury(entry.value),
                diastolic = Pressure.millimetersOfMercury(entry.second ?: return null),
                metadata = metadata,
            )
            Type.MINDFULNESS -> {
                // An entry is stamped when it was logged, and a session is logged when it ends.
                val start = at.minusSeconds((entry.value * SECONDS_PER_MINUTE).roundToLong().coerceAtLeast(1))
                MindfulnessSessionRecord(
                    startTime = start, startZoneOffset = zone.rules.getOffset(start), endTime = at, endZoneOffset = offset,
                    mindfulnessSessionType = MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_BREATHING,
                    metadata = metadata,
                )
            }
            Type.HEART_RATE -> HeartRateRecord(
                startTime = at, startZoneOffset = offset, endTime = at.plusSeconds(1), endZoneOffset = offset,
                samples = listOf(HeartRateRecord.Sample(at, entry.value.roundToLong())),
                metadata = metadata,
            )
            Type.HRV -> HeartRateVariabilityRmssdRecord(
                time = at, zoneOffset = offset, heartRateVariabilityMillis = entry.value, metadata = metadata,
            )
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    fun clientRecordId(entry: WearLinkEntry, salt: String = ""): String =
        saltedRecordId("$CLIENT_ID_PREFIX${entry.type.token}_${entry.epochMillis}", salt)

    private fun metadata(entry: WearLinkEntry, salt: String): Metadata {
        val device = Device(type = Device.TYPE_WATCH)
        val id = clientRecordId(entry, salt)
        return if (entry.measured) Metadata.activelyRecorded(device = device, clientRecordId = id) else Metadata.manualEntry(device = device, clientRecordId = id)
    }

    private const val SECONDS_PER_MINUTE = 60
}
