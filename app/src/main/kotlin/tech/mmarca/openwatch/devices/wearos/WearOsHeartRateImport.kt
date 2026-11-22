package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import java.time.Instant
import tech.mmarca.openwatch.devices.core.saltedRecordId

/**
 * Maps the watch's heart rate samples onto Health Connect records: one
 * `HeartRateRecord` per clock hour, keyed on the bucket's first sample, the
 * same shape the Garmin wellness import writes. A re-pull of the same samples
 * produces the same ids, so a second write updates rather than duplicates.
 *
 * Pure: no Android, so it is testable without a device.
 */
object WearOsHeartRateImport {

    const val CLIENT_ID_PREFIX = "wearos_hr_"

    fun records(samples: List<WearOsHeartRateSample>, salt: String = ""): List<Record> {
        if (samples.isEmpty()) return emptyList()
        val sorted = samples.distinctBy { it.time }.sortedBy { it.time }
        return sorted
            .groupBy { it.time.epochSecond / SECONDS_PER_HOUR }
            .values
            .map { bucket -> record(bucket, salt) }
    }

    private fun record(bucket: List<WearOsHeartRateSample>, salt: String): HeartRateRecord {
        val start = bucket.first().time
        val last = bucket.last().time
        val end = if (last.isAfter(start)) last else start.plusSeconds(1)
        return HeartRateRecord(
            startTime = start,
            startZoneOffset = null,
            endTime = end,
            endZoneOffset = null,
            samples = bucket.map { HeartRateRecord.Sample(time = it.time, beatsPerMinute = it.beatsPerMinute.toLong()) },
            metadata = Metadata.autoRecorded(
                device = Device(type = Device.TYPE_WATCH),
                clientRecordId = clientRecordId(start, salt),
            ),
        )
    }

    fun clientRecordId(bucketStart: Instant, salt: String = ""): String =
        saltedRecordId("$CLIENT_ID_PREFIX${bucketStart.toEpochMilli()}", salt)

    private const val SECONDS_PER_HOUR = 3600L
}
