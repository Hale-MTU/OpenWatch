package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.mmarca.healthcalculations.preferences.BiologicalSex
import tech.mmarca.healthcalculations.preferences.BodyProfile
import tech.mmarca.openwatch.devices.core.saltedRecordId
import tech.mmarca.openwatch.healthconnect.HealthConnectReads
import tech.mmarca.openwatch.wearlink.WorkoutMetrics

/** A workout's calories on the phone: the watch's own heart rate, read as far as its hour record reaches, and the watch's number. */
class WearOsWorkoutEnergyTest {

    /** A workout that starts in the middle of a clock hour, whose heart rate record began at the hour. */
    private val start = Instant.parse("2026-11-09T19:35:00Z")
    private val workout = WearOsWorkout(
        start = start,
        end = start.plusSeconds(1200),
        exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        steps = null,
        distanceMetres = null,
        ascentMetres = null,
        pauses = emptyList(),
        route = emptyList(),
    )
    private val body = BodyProfile(birthYear = 1990, weightKg = 72.0, heightCm = 180.0, sex = BiologicalSex.MALE)

    @Test
    fun `the calories rest on this watch's own hour record, which began before the workout, and are the watch's number`() = runTest {
        val hour = Instant.parse("2026-11-09T19:00:00Z")
        val own = heartRate(hour, saltedRecordId("wearos_hr_1", "s"), seconds = 3600, bpm = 150)
        val otherApp = heartRate(start, "samsung_1", seconds = 1200, bpm = 90)
        val asked = mutableListOf<Instant>()
        val reads = mockk<HealthConnectReads> {
            coEvery { grantedPermissions() } returns setOf(WearOsWorkoutImport.energyPermission)
            coEvery { forEachSyncRecordPage(HeartRateRecord::class, any(), any(), any()) } coAnswers {
                asked += secondArg<Instant>()
                // Health Connect finds a record by its bounds: the hour's record only for a read that reaches its start.
                val found = listOf(own, otherApp).filter { !it.startTime.isBefore(secondArg()) }
                arg<suspend (List<Record>) -> Unit>(3).invoke(found)
            }
        }
        val written = mutableListOf<Record>()
        val energy = WearOsWorkoutEnergy(reads, profile = { body }, insert = { written += it }, zone = { ZoneOffset.UTC })

        assertEquals(WearOsWorkoutEnergy.Outcome.Written, energy.write(workout, salt = "s"))

        assertEquals("an hour before the start, where the hour's record may begin", listOf(start.minusSeconds(3600)), asked)
        val beats = (0..1200L).map { WorkoutMetrics.Beat(start.plusSeconds(it).toEpochMilli(), 150) }
        val day = LocalDate.of(2026, 11, 9)
        val watch = WorkoutMetrics.summarize(WearOsWorkoutMetrics.linkWorkout(workout), beats, null, body, day)
        val record = written.single() as ActiveCaloriesBurnedRecord
        assertEquals(watch.activeKcal!!, record.energy.inKilocalories, 1e-6)
    }

    /** A heart rate record of [seconds] readings one a second from [from], under [id]. */
    private fun heartRate(from: Instant, id: String, seconds: Long, bpm: Long) = HeartRateRecord(
        startTime = from,
        startZoneOffset = null,
        endTime = from.plusSeconds(seconds),
        endZoneOffset = null,
        samples = (0 until seconds).map { HeartRateRecord.Sample(from.plusSeconds(it), bpm) },
        metadata = Metadata.autoRecorded(device = Device(type = Device.TYPE_WATCH), clientRecordId = id),
    )
}
