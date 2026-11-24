package tech.mmarca.openwatch.devices.blestandard

import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Pressure
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import tech.mmarca.openwatch.devices.core.measure.MeasurementRecords
import tech.mmarca.openwatch.devices.core.measure.PushedMeasurement
import tech.mmarca.openwatch.devices.core.saltedRecordId
import tech.mmarca.openwatch.domain.model.BleSensorDevice

/**
 * The family's measurements as Health Connect records, read back from what the sink kept: a
 * strap's minute, or the bytes a cuff, a scale or a meter sent, read again by the profile's
 * parser. Every id is `ble_standard_<the measurement's key>`, salted by the device, so a
 * measurement handed over twice is one record and two devices of the family never share one.
 * Each record is auto-recorded, by the kind of device it came from.
 */
@Singleton
class StandardMeasurementRecords @Inject constructor() : MeasurementRecords {

    override fun records(device: BleSensorDevice, measurement: PushedMeasurement): List<Record> {
        val key = measurement.key
        if (key.startsWith(HeartRateSamples.KEY_PREFIX)) {
            return HeartRateSamples.decode(measurement.payload)?.let { heartRate(device, key, it) }.orEmpty()
        }
        val value = ReceivedValue.decode(measurement.payload) ?: return emptyList()
        return when {
            key.startsWith(PushProfile.BLOOD_PRESSURE.keyPrefix) -> bloodPressure(device, key, value)
            key.startsWith(PushProfile.WEIGHT.keyPrefix) -> weight(device, key, value)
            key.startsWith(PushProfile.GLUCOSE.keyPrefix) -> glucose(device, key, value)
            else -> emptyList()
        }
    }

    /** One record per run, its samples as the strap sent them; a sample Health Connect would refuse is left out. */
    private fun heartRate(device: BleSensorDevice, key: String, run: HeartRateSamples): List<Record> {
        val samples = run.samples
            .filter { it.beatsPerMinute in HEART_RATE_RANGE }
            .map { HeartRateRecord.Sample(Instant.ofEpochMilli(it.atMillis), it.beatsPerMinute.toLong()) }
        if (samples.isEmpty()) return emptyList()
        val offset = ZoneOffset.ofTotalSeconds(run.zoneOffsetSeconds)
        return listOf(
            HeartRateRecord(
                startTime = samples.first().time,
                startZoneOffset = offset,
                endTime = samples.last().time,
                endZoneOffset = offset,
                samples = samples,
                metadata = metadata(device, key, Device.TYPE_CHEST_STRAP),
            ),
        )
    }

    /** The pressures, then the pulse the cuff measured with them as a heart rate. Health Connect takes mmHg. */
    private fun bloodPressure(device: BleSensorDevice, key: String, value: ReceivedValue): List<Record> {
        val reading = BloodPressureMeasurement.parse(value.bytes)?.takeIf { it.isUsable } ?: return emptyList()
        val (time, offset) = timeOf(reading.timeStamp, value)
        val pressure = BloodPressureRecord(
            time = time,
            zoneOffset = offset,
            metadata = metadata(device, key, Device.TYPE_UNKNOWN),
            systolic = Pressure.millimetersOfMercury(reading.systolicMmHg),
            diastolic = Pressure.millimetersOfMercury(reading.diastolicMmHg),
            bodyPosition = BloodPressureRecord.BODY_POSITION_UNKNOWN,
            measurementLocation = BloodPressureRecord.MEASUREMENT_LOCATION_UNKNOWN,
        )
        val pulse = reading.pulseRate?.toLong()?.takeIf { it in HEART_RATE_RANGE.first..HEART_RATE_RANGE.last }?.let { bpm ->
            HeartRateRecord(
                startTime = time,
                startZoneOffset = offset,
                endTime = time,
                endZoneOffset = offset,
                samples = listOf(HeartRateRecord.Sample(time, bpm)),
                metadata = metadata(device, "${key}_pulse", Device.TYPE_UNKNOWN),
            )
        }
        return listOfNotNull(pressure, pulse)
    }

    private fun weight(device: BleSensorDevice, key: String, value: ReceivedValue): List<Record> {
        val reading = WeightMeasurement.parse(value.bytes) ?: return emptyList()
        val kilograms = reading.weightKg ?: return emptyList()
        val (time, offset) = timeOf(reading.timeStamp, value)
        return listOf(WeightRecord(time, offset, Mass.kilograms(kilograms), metadata(device, key, Device.TYPE_SCALE)))
    }

    /**
     * A meter's record at its base time plus its offset, with where the sample came from and, when
     * its context came with it, how it stood to a meal. A check with control solution is no one's
     * blood, and is not written.
     */
    private fun glucose(device: BleSensorDevice, key: String, value: ReceivedValue): List<Record> {
        val reading = GlucoseMeasurement.parse(value.bytes) ?: return emptyList()
        val level = reading.mmolPerLiter?.takeIf { it.isFinite() } ?: return emptyList()
        if (reading.sampleType == GlucoseSampleType.CONTROL_SOLUTION) return emptyList()
        val context = value.context?.let(GlucoseContext::parse)?.takeIf { it.sequenceNumber == reading.sequenceNumber }
        val (time, offset) = timeOf(reading.baseTime, value, plusMinutes = reading.timeOffsetMinutes.toLong())
        return listOf(
            BloodGlucoseRecord(
                time = time,
                zoneOffset = offset,
                metadata = metadata(device, key, Device.TYPE_UNKNOWN),
                level = BloodGlucose.millimolesPerLiter(level),
                specimenSource = specimenSource(reading.sampleType),
                mealType = mealType(context?.carbohydrateId),
                relationToMeal = relationToMeal(context?.meal),
            ),
        )
    }

    /**
     * When a reading was taken: the device's own time stamp ([plusMinutes] after it, for a glucose
     * record's time offset), read in the phone's offset when it
     * came, since a profile's time has no zone; the time it came when the device sent none, sent
     * one its clock does not know, or one later than that (a clock that is wrong, never a reading
     * from the future).
     */
    private fun timeOf(stamp: GattDateTime?, value: ReceivedValue, plusMinutes: Long = 0): Pair<Instant, ZoneOffset> {
        val offset = ZoneOffset.ofTotalSeconds(value.zoneOffsetSeconds)
        val received = Instant.ofEpochMilli(value.receivedAtMillis)
        val stamped = stamp?.toLocalDateTime()?.plusMinutes(plusMinutes)?.toInstant(offset)
        return (stamped?.takeIf { !it.isAfter(received.plus(CLOCK_TOLERANCE)) } ?: received) to offset
    }

    private fun specimenSource(type: GlucoseSampleType?): Int = when (type) {
        GlucoseSampleType.CAPILLARY_WHOLE_BLOOD -> BloodGlucoseRecord.SPECIMEN_SOURCE_CAPILLARY_BLOOD
        GlucoseSampleType.CAPILLARY_PLASMA,
        GlucoseSampleType.VENOUS_PLASMA,
        GlucoseSampleType.ARTERIAL_PLASMA,
        GlucoseSampleType.UNDETERMINED_PLASMA,
        -> BloodGlucoseRecord.SPECIMEN_SOURCE_PLASMA
        GlucoseSampleType.VENOUS_WHOLE_BLOOD,
        GlucoseSampleType.ARTERIAL_WHOLE_BLOOD,
        GlucoseSampleType.UNDETERMINED_WHOLE_BLOOD,
        -> BloodGlucoseRecord.SPECIMEN_SOURCE_WHOLE_BLOOD
        GlucoseSampleType.INTERSTITIAL_FLUID -> BloodGlucoseRecord.SPECIMEN_SOURCE_INTERSTITIAL_FLUID
        GlucoseSampleType.CONTROL_SOLUTION, null -> BloodGlucoseRecord.SPECIMEN_SOURCE_UNKNOWN
    }

    private fun relationToMeal(meal: GlucoseMeal?): Int = when (meal) {
        GlucoseMeal.PREPRANDIAL -> BloodGlucoseRecord.RELATION_TO_MEAL_BEFORE_MEAL
        GlucoseMeal.POSTPRANDIAL -> BloodGlucoseRecord.RELATION_TO_MEAL_AFTER_MEAL
        GlucoseMeal.FASTING -> BloodGlucoseRecord.RELATION_TO_MEAL_FASTING
        GlucoseMeal.CASUAL, GlucoseMeal.BEDTIME -> BloodGlucoseRecord.RELATION_TO_MEAL_GENERAL
        null -> BloodGlucoseRecord.RELATION_TO_MEAL_UNKNOWN
    }

    /** The meal the context's carbohydrate names, where it is one Health Connect has (supper is a dinner). */
    private fun mealType(carbohydrateId: Int?): Int = when (carbohydrateId) {
        1 -> MealType.MEAL_TYPE_BREAKFAST
        2 -> MealType.MEAL_TYPE_LUNCH
        3, 6 -> MealType.MEAL_TYPE_DINNER
        4 -> MealType.MEAL_TYPE_SNACK
        else -> MealType.MEAL_TYPE_UNKNOWN
    }

    private fun metadata(device: BleSensorDevice, key: String, type: Int) = Metadata.autoRecorded(
        device = Device(type = type),
        clientRecordId = saltedRecordId(StandardProfiles.RECORD_ID_PREFIX + key, device.idSalt),
    )

    private companion object {
        /** What Health Connect takes as a heart rate. */
        val HEART_RATE_RANGE = 1..300

        /** How far a device's clock may run ahead of the phone's and still be believed. */
        val CLOCK_TOLERANCE: Duration = Duration.ofMinutes(10)
    }
}
