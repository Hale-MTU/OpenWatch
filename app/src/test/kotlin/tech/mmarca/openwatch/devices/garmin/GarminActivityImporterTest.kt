package tech.mmarca.openwatch.devices.garmin

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.TimeZone
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.mmarca.openwatch.core.performance.DefaultDispatcherProvider
import tech.mmarca.openwatch.data.repository.BodyProfileProvider
import tech.mmarca.openwatch.data.repository.OpenWatchPreferences
import tech.mmarca.openwatch.data.repository.contract.ActivityRepository
import tech.mmarca.openwatch.domain.model.ActivityRecordSource
import tech.mmarca.openwatch.domain.model.ActivityWriteRequest
import tech.mmarca.openwatch.domain.preferences.BodyProfile
import tech.mmarca.openwatch.domain.preferences.UnitSystem
import tech.mmarca.openwatch.activityimport.FitTestFiles
import tech.mmarca.openwatch.activityimport.FitTestPoint
import tech.mmarca.openwatch.activityimport.RouteElevationCorrector

/**
 * Only ACTIVITY files are considered, a file the parser rejects is skipped
 * rather than sinking the sync, and an imported tile corrects the route.
 */
class GarminActivityImporterTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val activityRepository = mockk<ActivityRepository>(relaxed = true)
    private val preferencesRepository = mockk<OpenWatchPreferences>(relaxed = true)
    private val bodyProfileProvider = mockk<BodyProfileProvider>()

    private fun file(type: GarminFileType, bytes: ByteArray = byteArrayOf(1, 2, 3)) =
        GarminDownloadedFile(
            entry = GarminDirectoryEntry(
                fileIndex = 7,
                type = type,
                fileNumber = 7,
                specificFlags = 0,
                fileFlags = 0,
                fileSize = bytes.size.toLong(),
                fileDate = Instant.parse("2026-06-10T08:00:00Z"),
            ),
            bytes = bytes,
        )

    @Test
    fun `nothing to do without activity files`() = runTest {
        val result = importer().import(
            listOf(file(GarminFileType.SLEEP), file(GarminFileType.MONITOR)),
        )

        assertEquals(GarminActivityImportResult(), result)
        coVerify(exactly = 0) { activityRepository.writeActivityEntries(any()) }
        coVerify(exactly = 0) { activityRepository.writeActivityEntry(any()) }
    }

    @Test
    fun `an undecodable activity file is skipped, never thrown`() = runTest {
        // Three junk bytes are not a FIT file; the import must swallow that per file.
        val result = importer().import(listOf(file(GarminFileType.ACTIVITY)))

        // Done with, not queued for a retry: parsing the same bytes again cannot help.
        assertEquals(GarminActivityImportResult(), result)
        coVerify(exactly = 0) { activityRepository.writeActivityEntries(any()) }
    }

    @Test
    fun `an empty download list is a no-op`() = runTest {
        assertEquals(GarminActivityImportResult(), importer().import(emptyList()))
    }

    @Test
    fun `without a tile the watch's altitudes and ascent stand`() = runTest {
        val written = slot<List<ActivityWriteRequest>>()
        coEvery { activityRepository.writeActivityEntries(capture(written)) } returns emptyList()

        importer().import(listOf(file(GarminFileType.ACTIVITY, rideWithAscent(totalAscentMeters = 999))))

        val request = written.captured.single()
        assertEquals(listOf(10.0, 22.0), request.routePoints.map { it.altitudeMeters!! })
        assertEquals(999.0, request.elevationGainedMeters!!, 0.01)
    }

    @Test
    fun `a time zone change after start-up neither shifts nor drops the activity`() = runTest {
        val systemZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Madrid"))
            val importer = importer() // Built once, at process start.
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York")) // The user flew west.
            val written = slot<List<ActivityWriteRequest>>()
            coEvery { activityRepository.writeActivityEntries(capture(written)) } returns emptyList()

            val result = importer.import(listOf(file(GarminFileType.ACTIVITY, rideWithAscent(totalAscentMeters = 12))))

            assertEquals(1, result.written)
            assertEquals(Instant.parse("2026-05-26T08:30:00Z"), written.captured.single().startTime)
        } finally {
            TimeZone.setDefault(systemZone)
        }
    }

    @Test
    fun `a watch activity is written as the watch recorded it, under a key of the file`() = runTest {
        val written = slot<List<ActivityWriteRequest>>()
        coEvery { activityRepository.writeActivityEntries(capture(written)) } returns emptyList()
        val bytes = rideWithAscent(totalAscentMeters = 12)

        importer().import(listOf(file(GarminFileType.ACTIVITY, bytes)))
        val first = written.captured.single()
        importer().import(listOf(file(GarminFileType.ACTIVITY, bytes)))

        assertEquals(ActivityRecordSource.WATCH, first.source)
        // The second sync of the same file must address the same records.
        assertEquals(first.importKey, written.captured.single().importKey)
        // Through the entry form the end was rounded up to 08:32:00.
        assertEquals(Instant.parse("2026-05-26T08:31:01Z"), first.endTime)
    }

    @Test
    fun `a workout skipped for a missing permission is reported for a retry`() = runTest {
        val importer = importer()
        coEvery { activityRepository.hasActivityWritePermission(any<ActivityWriteRequest>()) } returns false
        val ride = file(GarminFileType.ACTIVITY, rideWithAscent(totalAscentMeters = 12))

        val result = importer.import(listOf(ride))

        assertEquals(0, result.written)
        assertEquals(listOf(ride), result.missingPermission)
        assertEquals(listOf(ride), result.retry)
        coVerify(exactly = 0) { activityRepository.writeActivityEntries(any()) }
    }

    @Test
    fun `a workout whose write failed is reported for a retry`() = runTest {
        val importer = importer()
        coEvery { activityRepository.writeActivityEntries(any()) } throws IllegalStateException("rate limited")
        coEvery { activityRepository.writeActivityEntry(any()) } throws IllegalStateException("rate limited")
        val ride = file(GarminFileType.ACTIVITY, rideWithAscent(totalAscentMeters = 12))

        val result = importer.import(listOf(ride))

        assertEquals(0, result.written)
        assertEquals(listOf(ride), result.failedWrites)
        assertEquals(listOf(ride), result.retry)
    }

@Test
    fun `a workout without calories and a profile without weight is counted, so the sync can say so`() = runTest {
        val result = importer(profile = BodyProfile())
            .import(listOf(file(GarminFileType.ACTIVITY, rideWithAscent(totalAscentMeters = 12))))

        assertEquals(1, result.written)
        assertEquals(1, result.withoutCaloriesForWantOfWeight)
    }

    @Test
    fun `a profile that cannot be read makes no estimate and blames no weight`() = runTest {
        val importer = importer()
        coEvery { bodyProfileProvider.resolve() } throws IllegalStateException("read failed")
        val written = slot<List<ActivityWriteRequest>>()
        coEvery { activityRepository.writeActivityEntries(capture(written)) } returns listOf("id")

        val result = importer.import(listOf(file(GarminFileType.ACTIVITY, rideWithAscent(totalAscentMeters = 12))))

        assertEquals(1, result.written)
        assertEquals(0, result.withoutCaloriesForWantOfWeight)
        assertEquals(null, written.captured.single().activeCaloriesKcal)
    }

        private fun importer(profile: BodyProfile = BodyProfile(weightKg = 70.0)): GarminActivityImporter {
        every { preferencesRepository.unitSystem } returns UnitSystem.METRIC
        every { preferencesRepository.favoriteActivityExerciseType } returns null
        every { preferencesRepository.lastActivityExerciseType } returns null
        every { activityRepository.activityWritePermissions(any<ActivityWriteRequest>()) } returns emptySet()
        coEvery { activityRepository.hasActivityWritePermission(any<ActivityWriteRequest>()) } returns true

        coEvery { bodyProfileProvider.resolve() } returns profile
        return GarminActivityImporter(
            activityRepository,
            preferencesRepository,
            RouteElevationCorrector(),
            bodyProfileProvider,
        )
    }

    private fun rideWithAscent(totalAscentMeters: Int): ByteArray =
        FitTestFiles.activity(
            sport = 2,
            points = listOf(
                FitTestPoint(
                    time = Instant.parse("2026-05-26T08:30:00Z"),
                    latitude = 59.5000,
                    longitude = 24.5000,
                    altitudeMeters = 10.0,
                ),
                FitTestPoint(
                    time = Instant.parse("2026-05-26T08:31:00Z"),
                    latitude = 59.5010,
                    longitude = 24.5020,
                    altitudeMeters = 22.0,
                ),
            ),
            totalAscentMeters = totalAscentMeters,
        )
}
