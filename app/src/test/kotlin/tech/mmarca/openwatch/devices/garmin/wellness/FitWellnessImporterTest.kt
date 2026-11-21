package tech.mmarca.openwatch.devices.garmin.wellness

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.data.repository.ImportedRecordRepository
import tech.mmarca.openwatch.data.repository.partitionByWriteGrant
import tech.mmarca.openwatch.devices.garmin.GarminDirectoryEntry
import tech.mmarca.openwatch.devices.garmin.GarminDownloadedFile
import tech.mmarca.openwatch.devices.garmin.GarminFileType

/**
 * One withheld write grant costs its record type, not the import: an import that throws
 * keeps its files pending, and every later sync of the watch stops at them.
 */
class FitWellnessImporterTest {

    private val at = Instant.parse("2026-08-27T06:30:00Z")
    private val written = mutableListOf<Record>()
    private val importRepository = mockk<ImportedRecordRepository> {
        coEvery { insertImportedRecords(any()) } answers { written += firstArg<List<Record>>() }
    }
    private var granted = emptySet<String>()

    private fun importer() = FitWellnessImporter(
        importRepository = importRepository,
        wellnessRepository = mockk(relaxed = true),
        watermarkStore = mockk(relaxed = true),
        sleepMinuteRepository = mockk(relaxed = true),
        sleepEstimation = mockk(relaxed = true),
        grantedPermissions = { granted },
    )

    /** A Garmin weight file holding one 74.25 kg reading. */
    private fun weightFile() = GarminDownloadedFile(
        entry = GarminDirectoryEntry(
            fileIndex = 1,
            type = GarminFileType.ACTIVITY,
            fileNumber = 1,
            specificFlags = 0,
            fileFlags = 0,
            fileSize = 0,
            fileDate = at,
        ),
        bytes = fitWrap(
            FitW()
                .fileId(9)
                .def(local = 0, global = 30, fields = listOf(listOf(253, 4, 134), listOf(0, 2, 132)))
                .u8(0)
                .u32(fitTimestamp(at))
                .u16(7_425)
                .toBytes(),
        ),
    )

    @Test
    fun `a type whose write is not granted is left out and named, and the import goes on`() = runTest {
        val withheld = importer().import(listOf(weightFile()))

        assertEquals(setOf(WriteWeight), withheld)
        assertTrue(written.isEmpty())
        coVerify(exactly = 0) { importRepository.insertImportedRecords(any()) }
    }

    @Test
    fun `a granted type is written and nothing is named`() = runTest {
        granted = setOf(WriteWeight)

        val withheld = importer().import(listOf(weightFile()))

        assertTrue(withheld.isEmpty())
        assertEquals(74.25, (written.single() as WeightRecord).weight.inKilograms, 0.001)
    }

    @Test
    fun `records split by their type's write grant`() {
        val steps = StepsRecord(
            startTime = at,
            startZoneOffset = ZoneOffset.UTC,
            endTime = at.plusSeconds(60),
            endZoneOffset = ZoneOffset.UTC,
            count = 10,
            metadata = Metadata.manualEntry(clientRecordId = "s"),
        )
        val weight = WeightRecord(
            time = at,
            zoneOffset = ZoneOffset.UTC,
            weight = Mass.kilograms(70.0),
            metadata = Metadata.manualEntry(clientRecordId = "w"),
        )

        val (allowed, withheld) = partitionByWriteGrant(listOf(steps, weight), granted = setOf(WriteWeight))

        assertEquals(listOf<Record>(weight), allowed)
        assertEquals(setOf(HealthPermission.getWritePermission(StepsRecord::class)), withheld)
    }

    private companion object {
        val WriteWeight = HealthPermission.getWritePermission(WeightRecord::class)
    }
}
