package tech.mmarca.openwatch.activityimport

import androidx.health.connect.client.records.ExerciseSessionRecord
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openwatch.domain.insights.WorkoutEnergyInput
import tech.mmarca.openwatch.domain.insights.WorkoutEnergyMethod
import tech.mmarca.openwatch.domain.insights.WorkoutEnergyResult
import tech.mmarca.openwatch.domain.model.ActivityRecordSource
import tech.mmarca.openwatch.domain.preferences.BodyProfile
import tech.mmarca.openwatch.activityimport.DefaultActivityEntryTypes

/** A file imported with nobody to review it must reach Health Connect as the file says, once. */
class RouteFileActivityRequestTest {

    private val cycling = DefaultActivityEntryTypes.first {
        it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_BIKING
    }

    private fun ride(startSecond: Int = 17, endSecond: Int = 43): ByteArray = FitTestFiles.activity(
        sport = 2,
        points = listOf(
            FitTestPoint(Instant.parse("2026-05-26T08:30:%02dZ".format(startSecond)), 59.5000, 24.5000, 10.0),
            FitTestPoint(Instant.parse("2026-05-26T09:12:%02dZ".format(endSecond)), 59.5100, 24.5200, 22.0),
        ),
        totalAscentMeters = 12,
    )

    @Test
    fun `the start keeps its seconds and the end is not rounded up to a minute`() {
        val request = RouteFileParser.parseFile(ride()).toImportWriteRequest(cycling, ActivityRecordSource.WATCH, profile = null)!!.request

        // Through the entry form this became 08:30:00 to 09:13:00.
        assertEquals(Instant.parse("2026-05-26T08:30:17Z"), request.startTime)
        assertEquals(Instant.parse("2026-05-26T09:12:44Z"), request.endTime)
        assertEquals(request.routePoints.first().time, request.startTime)
    }

    @Test
    fun `the same file gives the same key whatever it is called`() {
        val first = RouteFileParser.parseFile(ride(), fileName = "activity_7.fit")
            .toImportWriteRequest(cycling, ActivityRecordSource.WATCH, profile = null)!!.request
        val again = RouteFileParser.parseFile(ride(), fileName = "2026-05-26 ride.fit")
            .toImportWriteRequest(cycling, ActivityRecordSource.FILE, profile = null)!!.request
        val other = RouteFileParser.parseFile(ride(endSecond = 44))
            .toImportWriteRequest(cycling, ActivityRecordSource.WATCH, profile = null)!!.request

        assertNotNull(first.importKey)
        assertEquals(first.importKey, again.importKey)
        assertNotEquals(first.importKey, other.importKey)
    }

    @Test
    fun `the request says where the file came from`() {
        val parsed = RouteFileParser.parseFile(ride())

        assertEquals(ActivityRecordSource.WATCH, parsed.toImportWriteRequest(cycling, ActivityRecordSource.WATCH, profile = null)!!.request.source)
        assertEquals(ActivityRecordSource.FILE, parsed.toImportWriteRequest(cycling, ActivityRecordSource.FILE, profile = null)!!.request.source)
    }

    @Test
    fun `a route with no time of its own is left to the entry form`() {
        val untimed = RouteFileParser.parseFile(ride()).copy(hasImportedTimeRange = false)

        assertNull(untimed.toImportWriteRequest(cycling, ActivityRecordSource.FILE, profile = null))
    }

    @Test
    fun `a file that was not parsed from bytes has no key and is refused`() {
        val keyless = RouteFileParser.parseFile(ride()).copy(contentKey = null)

        // A random id here would bring the duplicates back.
        assertNull(keyless.toImportWriteRequest(cycling, ActivityRecordSource.FILE, profile = null))
    }

    @Test
    fun `a file without calories gets the estimate OpenVitals' form makes, from the body profile`() {
        val imported = RouteFileParser.parseFile(ride())
            .toImportWriteRequest(cycling, ActivityRecordSource.WATCH, profile = BodyProfile(weightKg = 70.0))!!

        // 42 whole minutes of cycling, Compendium 01014 at 7.0 MET, 70 kg. No sex, so the
        // resting share is 1 MET: active (7.0 - 1) x 3.5 x 70 x 42 x 5 / 1000 = 308.7 kcal.
        assertEquals(309.0, imported.request.activeCaloriesKcal!!, 0.0)
        assertEquals(360.0, imported.request.totalCaloriesKcal!!, 0.0)
        assertEquals(
            WorkoutEnergyMethod.COMPENDIUM_MET,
            (imported.energyEstimate as WorkoutEnergyResult.Estimate).method,
        )
    }

    @Test
    fun `without a weight there are no calories, and the result says the weight is missing`() {
        val imported = RouteFileParser.parseFile(ride())
            .toImportWriteRequest(cycling, ActivityRecordSource.WATCH, profile = BodyProfile())!!

        assertNull(imported.request.activeCaloriesKcal)
        assertNull(imported.request.totalCaloriesKcal)
        assertEquals(
            WorkoutEnergyResult.MissingInputs(setOf(WorkoutEnergyInput.WEIGHT)),
            imported.energyEstimate,
        )
    }
}
