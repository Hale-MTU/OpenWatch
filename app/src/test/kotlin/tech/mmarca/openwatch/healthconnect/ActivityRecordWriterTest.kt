package tech.mmarca.openwatch.healthconnect

import android.util.Log
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlin.reflect.KClass
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.mmarca.openwatch.domain.model.ActivityRecordSource
import tech.mmarca.openwatch.domain.model.ActivityWriteRequest
import tech.mmarca.openwatch.domain.model.BleHeartRateSample
import tech.mmarca.openwatch.domain.model.BleRecordingSampleBuffer
import tech.mmarca.openwatch.domain.model.ExerciseRoutePoint
import tech.mmarca.openwatch.domain.model.importSessionClientRecordId

/**
 * The write path copied from OpenVitals, against the fake client: a watch
 * file yields a session plus its totals and series, under the client ids
 * OpenVitals would have used, so a file imported by either app is one record.
 */
class ActivityRecordWriterTest {

    private val fake = FakeHealthConnectClient()
    private val writer = ActivityRecordWriter { fake }

    // The JVM android.jar stub throws on every Log call.
    @Before
    fun silenceLog() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun restoreLog() = unmockkStatic(Log::class)

    @Test
    fun `an imported activity writes the session and its totals under keys of the file`() = runTest {
        val request = request(importKey = "fit:abc123")

        val id = writer.writeActivityEntry(request)

        val sessions = fake.all(ExerciseSessionRecord::class)
        assertEquals(1, sessions.size)
        val session = sessions.single()
        assertEquals(request.importSessionClientRecordId(), session.metadata.clientRecordId)
        assertEquals(Metadata.RECORDING_METHOD_UNKNOWN, session.metadata.recordingMethod)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_BIKING, session.exerciseType)
        assertTrue(session.exerciseRouteResult is androidx.health.connect.client.records.ExerciseRouteResult.Data)
        assertTrue(id.isNotBlank())

        val distance = fake.all(DistanceRecord::class).single()
        assertEquals(1500.0, distance.distance.inMeters, 0.001)
        assertEquals("openvitals_activity_distance_${T0.toEpochMilli()}_fit:abc123", distance.metadata.clientRecordId)
        val steps = fake.all(StepsRecord::class).single()
        assertEquals(2000L, steps.count)
        val heartRate = fake.all(HeartRateRecord::class).single()
        assertEquals(2, heartRate.samples.size)
    }

    @Test
    fun `many activities land in one call, each under its own key`() = runTest {
        val first = request(importKey = "fit:one")
        val second = request(importKey = "fit:two", start = T0.plusSeconds(7200), end = T0.plusSeconds(10800))

        val ids = writer.writeActivityEntries(listOf(first, second))

        assertEquals(listOf(first.importSessionClientRecordId(), second.importSessionClientRecordId()), ids)
        assertEquals(2, fake.all(ExerciseSessionRecord::class).size)
        assertEquals(2, fake.all(DistanceRecord::class).size)
    }

    @Test
    fun `a request whose end precedes its start is refused before anything is written`() = runTest {
        val request = request(importKey = "fit:bad", start = T0, end = T0.minusSeconds(60))

        val error = runCatching { writer.writeActivityEntry(request) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(fake.all(ExerciseSessionRecord::class).isEmpty())
    }

    private fun request(importKey: String, start: Instant = T0, end: Instant = T0.plusSeconds(3600)) =
        ActivityWriteRequest(
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            startTime = start,
            endTime = end,
            title = "Ride",
            distanceMeters = 1500.0,
            stepsCount = 2000L,
            routePoints = listOf(
                ExerciseRoutePoint(start, 59.5, 24.5, null, null, null),
                ExerciseRoutePoint(start.plusSeconds(60), 59.501, 24.502, null, null, null),
            ),
            bleSamples = BleRecordingSampleBuffer(
                heartRateSamples = listOf(
                    BleHeartRateSample(time = start.plusSeconds(10), beatsPerMinute = 120L),
                    BleHeartRateSample(time = start.plusSeconds(20), beatsPerMinute = 125L),
                ),
            ),
            source = ActivityRecordSource.FILE,
            importKey = importKey,
        )

    private suspend fun <T : androidx.health.connect.client.records.Record> FakeHealthConnectClient.all(
        type: KClass<T>,
    ): List<T> =
        readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(T0.minusSeconds(86_400), T0.plusSeconds(86_400))))
            .records

    private companion object {
        val T0: Instant = Instant.parse("2026-03-10T10:00:00Z")
    }
}
