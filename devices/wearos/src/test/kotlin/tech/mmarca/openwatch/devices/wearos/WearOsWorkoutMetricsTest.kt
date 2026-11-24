package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.healthcalculations.preferences.BodyProfile
import tech.mmarca.openwatch.data.repository.HealthRecordStore
import tech.mmarca.openwatch.data.repository.contract.FakeDeviceStateRepository
import tech.mmarca.openwatch.devices.core.saltedRecordId
import tech.mmarca.openwatch.healthconnect.HealthConnectReads
import tech.mmarca.openwatch.wearlink.WorkoutMetrics

class WearOsWorkoutMetricsTest {

    private val start = Instant.parse("2026-11-09T19:35:00Z")
    private val workout = WearOsWorkout(
        start = start,
        end = start.plusSeconds(3600),
        exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        steps = null,
        distanceMetres = null,
        ascentMetres = null,
        pauses = emptyList(),
        route = emptyList(),
    )

    private fun summary(vo2: Double? = null, splits: List<WorkoutMetrics.Split> = emptyList()) = WorkoutMetrics.Summary(
        averageBpm = 120, maxBpm = 160, zoneSeconds = listOf(600L, 1200L, 1800L, 0L, 0L), trimp = 85.4,
        activeKcal = null, totalKcal = null, splits = splits, paceSecondsPerKm = null, vo2Max = vo2,
    )

    @Test
    fun `the notes name the figures`() {
        assertEquals("Heart rate avg 120, max 160 bpm. Zones (min) 10/20/30/0/0. TRIMP 85", WearOsWorkoutMetrics.notes(summary()))
        assertEquals(
            "Heart rate avg 120, max 160 bpm. Zones (min) 10/20/30/0/0. TRIMP 85. Recovery 1 min 25, 2 min 41 bpm",
            WearOsWorkoutMetrics.notes(summary().copy(recovery = WorkoutMetrics.Recovery(25, 41))),
        )
        val none = WorkoutMetrics.Summary(null, null, List(5) { 0L }, null, null, null, emptyList(), null, null)
        assertNull(WearOsWorkoutMetrics.notes(none))
    }

    @Test
    fun `the splits are laps of a kilometre inside the session`() {
        val inside = WorkoutMetrics.Split(0, start.toEpochMilli(), start.plusSeconds(300).toEpochMilli(), 300)
        val past = WorkoutMetrics.Split(1, start.plusSeconds(3500).toEpochMilli(), start.plusSeconds(3700).toEpochMilli(), 200)

        val lap = WearOsWorkoutMetrics.laps(workout, summary(splits = listOf(inside, past))).single()

        assertEquals(start, lap.startTime)
        assertEquals(1000.0, lap.length!!.inMeters, 0.0)
    }

    @Test
    fun `a VO2max needs its grant and Health Connect's range, and its id carries the watch's salt`() {
        val all = setOf(WearOsWorkoutMetrics.vo2Permission)

        val record = WearOsWorkoutMetrics.vo2Record(workout, summary(vo2 = 48.2), ZoneOffset.UTC, all, salt = "b")!!

        assertEquals(48.2, record.vo2MillilitersPerMinuteKilogram, 0.0)
        assertEquals(workout.end, record.time)
        assertEquals(saltedRecordId("wearos_workout_vo2_${start.toEpochMilli()}", "b"), record.metadata.clientRecordId)
        assertNull(WearOsWorkoutMetrics.vo2Record(workout, summary(vo2 = 48.2), ZoneOffset.UTC, emptySet()))
        assertNull(WearOsWorkoutMetrics.vo2Record(workout, summary(vo2 = 140.0), ZoneOffset.UTC, all))
        assertNull(WearOsWorkoutMetrics.vo2Record(workout, summary(), ZoneOffset.UTC, all))
    }

    @Test
    fun `the figures rest on this watch's own heart rate, not another app's or another watch's`() = runTest {
        val before = heartRate(start.minusSeconds(600), "wearos_hr_1", 600, bpm = 180)
        val own = heartRate(start, "wearos_hr_2", 300, bpm = 140)
        val otherWatch = heartRate(start.plusSeconds(60), saltedRecordId("wearos_hr_3", "other"), 200, bpm = 190)
        val otherApp = heartRate(start.plusSeconds(60), "samsung_1", 200, bpm = 190)
        val reads = mockk<HealthConnectReads> {
            coEvery { readMaxHeartRate(any(), any()) } returns null
            coEvery { forEachSyncRecordPage(HeartRateRecord::class, any(), any(), any()) } coAnswers {
                arg<suspend (List<Record>) -> Unit>(3).invoke(listOf(before, own, otherWatch, otherApp))
            }
            coEvery { forEachSyncRecordPage(neq(HeartRateRecord::class), any(), any(), any()) } returns Unit
        }
        val figures = WearOsWorkoutFigures(reads, profile = { BodyProfile(birthYear = 1990) }, zone = { ZoneOffset.UTC })

        val summary = figures.summarize(workout.copy(end = start.plusSeconds(300)), salt = "")!!

        assertEquals("neither the readings before the session nor another source's count", 140, summary.maxBpm)
        assertTrue("the zones rest on the age's maximum", summary.zoneSeconds.sum() > 0)
    }

    @Test
    fun `a recovery is read only where the two minutes after the stop hold a reading a second`() = runTest {
        val end = start.plusSeconds(300)
        val session = heartRate(start, "wearos_hr_1", 300, bpm = 160)
        // Two minutes after the stop: a second each, falling from 160 to 130 at one minute and 110 at two.
        val after = HeartRateRecord(
            startTime = end.plusSeconds(1),
            startZoneOffset = null,
            endTime = end.plusSeconds(125),
            endZoneOffset = null,
            samples = (1..125L).map { second -> HeartRateRecord.Sample(end.plusSeconds(second), if (second < 90) 130L else 110L) },
            metadata = Metadata.autoRecorded(device = Device(type = Device.TYPE_WATCH), clientRecordId = "wearos_hr_2"),
        )
        fun figures(records: List<Record>) = WearOsWorkoutFigures(
            mockk {
                coEvery { readMaxHeartRate(any(), any()) } returns null
                coEvery { forEachSyncRecordPage(HeartRateRecord::class, any(), any(), any()) } coAnswers {
                    arg<suspend (List<Record>) -> Unit>(3).invoke(records)
                }
                coEvery { forEachSyncRecordPage(neq(HeartRateRecord::class), any(), any(), any()) } returns Unit
            },
            profile = { BodyProfile(birthYear = 1990) },
            zone = { ZoneOffset.UTC },
        )

        val measured = figures(listOf(session, after)).summarize(workout.copy(end = end), salt = "")!!
        val none = figures(listOf(session)).summarize(workout.copy(end = end), salt = "")!!

        assertEquals(WorkoutMetrics.Recovery(oneMinuteBpm = 30, twoMinutesBpm = 50), measured.recovery)
        assertNull(none.recovery)
    }

    @Test
    fun `the sync writes the figures with the session, and the session without them when they fail`() = runTest {
        val repository = mockk<HealthRecordStore>()
        val written = slot<List<Record>>()
        coEvery { repository.insertImportedRecords(capture(written)) } returns Unit
        val cursors = WearOsSyncCursorStore(FakeDeviceStateRepository())
        val split = WorkoutMetrics.Split(0, start.toEpochMilli(), start.plusSeconds(300).toEpochMilli(), 300)
        var figures: () -> WorkoutMetrics.Summary? = { summary(vo2 = 50.0, splits = listOf(split)) }
        val granted = WearOsWorkoutImport.permissions(workout) + WearOsWorkoutMetrics.vo2Permission
        val sync = WearOsWorkoutSync(repository, cursors, { granted }, { ZoneOffset.UTC }, summarize = { _, _ -> figures() })
        val page = WearOsWorkoutPage(listOf(workout), hasMore = false)

        sync.sync("watch") { since -> page.takeIf { since == Instant.EPOCH } ?: WearOsWorkoutPage(emptyList(), false) }

        val session = written.captured.filterIsInstance<ExerciseSessionRecord>().single()
        assertEquals(1, session.laps.size)
        assertEquals("Heart rate avg 120, max 160 bpm. Zones (min) 10/20/30/0/0. TRIMP 85", session.notes)
        assertEquals(50.0, written.captured.filterIsInstance<Vo2MaxRecord>().single().vo2MillilitersPerMinuteKilogram, 0.0)

        figures = { throw IllegalStateException("no heart rate read") }
        cursors.setWorkoutCursor("watch", Instant.EPOCH)
        sync.sync("watch") { since -> page.takeIf { since == Instant.EPOCH } ?: WearOsWorkoutPage(emptyList(), false) }

        val plain = written.captured.single() as ExerciseSessionRecord
        assertTrue(plain.laps.isEmpty())
        assertNull(plain.notes)
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
