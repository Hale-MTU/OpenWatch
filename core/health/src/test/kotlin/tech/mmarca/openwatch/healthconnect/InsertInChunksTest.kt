package tech.mmarca.openwatch.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class InsertInChunksTest {

    private val t0 = Instant.parse("2026-11-21T00:00:00Z")
    private val requests = mutableListOf<List<Record>>()
    private val client = mockk<HealthConnectClient> {
        coEvery { insertRecords(any()) } answers {
            requests += firstArg<List<Record>>()
            mockk(relaxed = true)
        }
    }

    private fun minute(index: Int) = StepsRecord(
        startTime = t0.plusSeconds(60L * index),
        startZoneOffset = ZoneOffset.UTC,
        endTime = t0.plusSeconds(60L * index + 60),
        endZoneOffset = ZoneOffset.UTC,
        count = 1,
        metadata = Metadata.manualEntry(clientRecordId = "m$index", clientRecordVersion = 1),
    )

    @Test
    fun `a week of minutes goes in requests of at most a thousand, in order`() = runTest {
        val records = List(2 * MaxRecordsPerInsert + 80) { minute(it) }

        client.insertInChunks(records)

        assertEquals(listOf(MaxRecordsPerInsert, MaxRecordsPerInsert, 80), requests.map { it.size })
        assertEquals(records, requests.flatten())
    }

    @Test
    fun `nothing to write is no request`() = runTest {
        client.insertInChunks(emptyList())

        assertEquals(0, requests.size)
    }
}
