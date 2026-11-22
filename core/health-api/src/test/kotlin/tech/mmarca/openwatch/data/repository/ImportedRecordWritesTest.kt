package tech.mmarca.openwatch.data.repository

import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import tech.mmarca.openwatch.healthconnect.HealthConnectSyncDisabledException
import tech.mmarca.openwatch.healthconnect.MaxRecordsPerInsert

class ImportedRecordWritesTest {

    private val t0 = Instant.parse("2026-10-07T10:00:00Z")
    private val store = FakeHealthRecordStore()

    private fun record(id: String, minute: Long, version: Long = 1, count: Long = 10) = StepsRecord(
        startTime = t0.plusSeconds(60 * minute),
        startZoneOffset = ZoneOffset.UTC,
        endTime = t0.plusSeconds(60 * minute + 60),
        endZoneOffset = ZoneOffset.UTC,
        count = count,
        metadata = Metadata.manualEntry(clientRecordId = id, clientRecordVersion = version),
    )

    private suspend fun storeFirst(vararg records: StepsRecord) {
        store.insertImportedRecords(records.toList())
        store.inserts.clear()
    }

    @Test
    fun `on the platform nothing is refused, read or tried twice`() = runTest {
        storeFirst(record("b", 1, version = 5))

        val outcomes = store.insertWithOutcomes(listOf(record("a", 0), record("b", 1, version = 5, count = 99), record("c", 2)))

        assertEquals(List(3) { RecordWrite.WRITTEN }, outcomes)
        assertEquals(listOf(listOf("a", "b", "c")), store.inserts)
        assertEquals(0, store.reads)
        // An equal version replaced what was there, as measured.
        assertEquals(99L, (store.stored.getValue("b") as StepsRecord).count)
    }

    @Test
    fun `a refused batch is diagnosed by one read and written again without what is stored`() = runTest {
        store.provider = FakeHealthRecordStore.Provider.REFUSING_APK
        storeFirst(record("b", 1))

        val outcomes = store.insertWithOutcomes(listOf(record("a", 0), record("b", 1), record("c", 2)))

        assertEquals(listOf(RecordWrite.WRITTEN, RecordWrite.PRESENT, RecordWrite.WRITTEN), outcomes)
        assertEquals(listOf(listOf("a", "b", "c"), listOf("a", "c")), store.inserts)
        assertEquals(1, store.reads)
    }

    @Test
    fun `a newer version of a stored id goes in again where the provider takes it`() = runTest {
        store.provider = FakeHealthRecordStore.Provider.REFUSING_APK
        store.refusesNewerVersions = false
        storeFirst(record("b", 1, version = 1), record("d", 3, version = 1))

        val outcomes = store.insertWithOutcomes(listOf(record("b", 1, version = 2), record("d", 3, version = 1)))

        assertEquals(listOf(RecordWrite.WRITTEN, RecordWrite.PRESENT), outcomes)
        assertEquals(listOf(listOf("b", "d"), listOf("b")), store.inserts)
        assertEquals(2L, store.stored.getValue("b").metadata.clientRecordVersion)
    }

    @Test
    fun `a provider that refuses even a newer version keeps the stored record, and the rest lands`() = runTest {
        store.provider = FakeHealthRecordStore.Provider.REFUSING_APK
        storeFirst(record("b", 1, version = 1))

        val outcomes = store.insertWithOutcomes(listOf(record("a", 0), record("b", 1, version = 2)))

        assertEquals(listOf(RecordWrite.WRITTEN, RecordWrite.PRESENT), outcomes)
        assertEquals(listOf(listOf("a", "b"), listOf("a", "b"), listOf("a")), store.inserts)
        assertEquals(1L, store.stored.getValue("b").metadata.clientRecordVersion)
    }

    @Test
    fun `a long list goes in batches, and only the refused batch is read`() = runTest {
        store.provider = FakeHealthRecordStore.Provider.REFUSING_APK
        val records = (0 until 2 * MaxRecordsPerInsert + 2).map { record("r$it", it.toLong()) }
        storeFirst(records[MaxRecordsPerInsert + 5])

        val outcomes = store.insertWithOutcomes(records)

        assertEquals(RecordWrite.PRESENT, outcomes[MaxRecordsPerInsert + 5])
        assertEquals(records.size - 1, outcomes.count { it == RecordWrite.WRITTEN })
        // Three batches, the second refused and written again without the stored record.
        assertEquals(
            listOf(MaxRecordsPerInsert, MaxRecordsPerInsert, MaxRecordsPerInsert - 1, 2),
            store.inserts.map { it.size },
        )
        assertEquals(1, store.reads)
    }

    @Test
    fun `a refusal the read cannot explain is thrown as it came`() = runTest {
        val refusal = IllegalArgumentException("bad record")
        store.failure = { refusal }

        val thrown = runCatching { store.insertWithOutcomes(listOf(record("a", 0))) }.exceptionOrNull()

        assertSame(refusal, thrown)
        assertEquals(1, store.reads)
    }

    @Test
    fun `a missing grant or a paused sync is thrown without a read`() = runTest {
        for (refusal in listOf(SecurityException("WRITE_STEPS"), HealthConnectSyncDisabledException())) {
            store.failure = { refusal }
            assertSame(refusal, runCatching { store.insertWithOutcomes(listOf(record("a", 0))) }.exceptionOrNull())
        }
        assertEquals(0, store.reads)
    }

    @Test
    fun `read first, only what is absent or newer is written, on the platform too`() = runTest {
        storeFirst(record("b", 1, version = 5), record("c", 2, version = 5))

        val outcomes = store.insertNewer(listOf(record("a", 0), record("b", 1, version = 5), record("c", 2, version = 6)))

        assertEquals(listOf(RecordWrite.WRITTEN, RecordWrite.PRESENT, RecordWrite.WRITTEN), outcomes)
        assertEquals(listOf(listOf("a", "c")), store.inserts)
        assertEquals(1, store.reads)
    }

    @Test
    fun `a replacement is one insert on the platform, and a delete first where the provider refuses`() = runTest {
        storeFirst(record("b", 1, version = 1))
        store.replaceImportedRecord(record("b", 1, version = 2))
        assertEquals(listOf(listOf("b")), store.inserts)

        store.provider = FakeHealthRecordStore.Provider.REFUSING_APK
        store.inserts.clear()
        store.replaceImportedRecord(record("b", 1, version = 3, count = 7))

        assertEquals(listOf(listOf("b"), listOf("b")), store.inserts)
        assertEquals(7L, (store.stored.getValue("b") as StepsRecord).count)
    }

    @Test
    fun `a replacement refused for a record that is not there is thrown`() = runTest {
        val refusal = IllegalArgumentException("bad record")
        store.failure = { refusal }

        assertSame(refusal, runCatching { store.replaceImportedRecord(record("a", 0)) }.exceptionOrNull())
    }
}
