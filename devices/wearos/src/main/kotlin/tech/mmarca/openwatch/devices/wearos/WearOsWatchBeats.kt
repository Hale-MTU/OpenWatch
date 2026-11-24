package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.records.HeartRateRecord
import java.time.Duration
import java.time.Instant
import tech.mmarca.openwatch.devices.core.recordIdSalt
import tech.mmarca.openwatch.healthconnect.HealthConnectReads
import tech.mmarca.openwatch.wearlink.WorkoutMetrics

/**
 * The heart rate the watch with id salt [salt] recorded from [from] to
 * [until], both included, oldest first: its own hour records only
 * ([WearOsHeartRateImport]), so another app's readings of the same minutes
 * do not count twice, and the readings are the ones the watch's own figures
 * read from its store. Health Connect finds a record by its bounds, so the
 * read reaches an hour record's span further both ways: the record of the
 * clock hour a workout began in starts before the workout. What a
 * workout's figures ([WearOsWorkoutFigures]) and its calories
 * ([WearOsWorkoutEnergy]) both read.
 */
internal suspend fun HealthConnectReads.watchBeats(from: Instant, until: Instant, salt: String): List<WorkoutMetrics.Beat> {
    val beats = ArrayList<WorkoutMetrics.Beat>()
    forEachSyncRecordPage(HeartRateRecord::class, from.minus(HOUR_RECORD_SPAN), until.plus(HOUR_RECORD_SPAN)) { page ->
        for (record in page.filterIsInstance<HeartRateRecord>()) {
            val id = record.metadata.clientRecordId ?: continue
            if (!id.startsWith(WearOsHeartRateImport.CLIENT_ID_PREFIX) || recordIdSalt(id) != salt) continue
            for (sample in record.samples) {
                if (sample.time < from || sample.time > until) continue
                beats += WorkoutMetrics.Beat(sample.time.toEpochMilli(), sample.beatsPerMinute.toInt())
            }
        }
    }
    return beats.sortedBy { it.epochMillis }
}

/** The longest a watch's heart rate record spans: its clock hour. */
private val HOUR_RECORD_SPAN: Duration = Duration.ofHours(1)
