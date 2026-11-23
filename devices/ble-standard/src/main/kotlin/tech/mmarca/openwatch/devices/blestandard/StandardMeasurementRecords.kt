package tech.mmarca.openwatch.devices.blestandard

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import tech.mmarca.openwatch.devices.core.measure.MeasurementRecords
import tech.mmarca.openwatch.devices.core.measure.PushedMeasurement
import tech.mmarca.openwatch.devices.core.saltedRecordId
import tech.mmarca.openwatch.domain.model.BleSensorDevice

/**
 * The family's measurements as Health Connect records, read back from what the sink kept. Every
 * id is `ble_standard_<the measurement's key>`, salted by the device, so a measurement handed
 * over twice is one record and two devices of the family never share one. Each record is
 * auto-recorded, by the kind of device it came from.
 */
@Singleton
class StandardMeasurementRecords @Inject constructor() : MeasurementRecords {

    override fun records(device: BleSensorDevice, measurement: PushedMeasurement): List<Record> = when {
        measurement.key.startsWith(HeartRateSamples.KEY_PREFIX) ->
            HeartRateSamples.decode(measurement.payload)?.let { heartRate(device, measurement.key, it) }.orEmpty()
        else -> emptyList()
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

    private fun metadata(device: BleSensorDevice, key: String, type: Int) = Metadata.autoRecorded(
        device = Device(type = type),
        clientRecordId = saltedRecordId(StandardProfiles.RECORD_ID_PREFIX + key, device.idSalt),
    )

    private companion object {
        /** What Health Connect takes as a heart rate. */
        val HEART_RATE_RANGE = 1..300
    }
}
