package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.data.repository.HealthRecordStore
import tech.mmarca.openwatch.data.repository.contract.FakeDeviceStateRepository
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration
import tech.mmarca.openwatch.healthconnect.HealthConnectReads
import tech.mmarca.openwatch.wearlink.WearLinkEntries
import tech.mmarca.openwatch.wearlink.WearLinkEntry
import tech.mmarca.openwatch.wearlink.WearLinkEntry.Type
import tech.mmarca.openwatch.wearlink.WearLinkProtocol
import tech.mmarca.openwatch.wearlink.WearLinkSince

/** The entries made on the watch into Health Connect: a grant held back, a type the phone cannot keep, the cursor. */
class WearOsEntrySyncTest {

    private val t0 = 1_794_000_000_000L
    private val cursors = WearOsSyncCursorStore(FakeDeviceStateRepository())
    private val store = MemoryStore()
    private val healthConnect = mockk<HealthConnectReads>(relaxed = true)
    private val sync = WearOsEntrySync(store, healthConnect, cursors) { ZoneOffset.UTC }

    /** The watch's `lg` lines in pages, each page ending with the cursor `s.<page>`; what each pull asked with. */
    private val pages = mutableListOf<List<String>>()
    private val asked = mutableListOf<String>()

    private val nodePort = object : WearOsNodePort {
        override suspend fun checkStatus(targetAddress: String?, targetName: String?) = WearOsCompanionStatus()

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Any> pullSeries(targetAddress: String?, targetName: String?, series: WearSeries<T>, cursor: String): WearOsSeriesPage<T> {
            check(series.key == WearLinkProtocol.CAP_ENTRIES)
            asked += cursor
            val at = if (cursor.startsWith("s.")) cursor.removePrefix("s.").toInt() else 0
            val lines = pages.getOrElse(at) { emptyList() }
            val next = if (lines.isEmpty()) cursor else "s.${at + 1}"
            return WearOsSeriesPage(lines.mapNotNull(series.parse), next, hasMore = at + 1 < pages.size) as WearOsSeriesPage<T>
        }

        override suspend fun liveHeartRate(targetAddress: String?, targetName: String?, onSample: (WearOsHeartRateSample) -> Unit) = false
        override suspend fun pullWatchSettings(targetAddress: String?, targetName: String?): Map<String, String>? = null
        override suspend fun setWatchSetting(targetAddress: String?, targetName: String?, key: String, value: String) = false
        override suspend fun pullWorkouts(targetAddress: String?, targetName: String?, since: Instant): WearOsWorkoutPage? = null
        override suspend fun notifyPhoneActive(targetAddress: String?, targetName: String?, at: Instant) = false
        override suspend fun pushDays(targetAddress: String?, targetName: String?, days: List<tech.mmarca.openwatch.wearlink.DaySummary>) = false
    }

    private val reader = WearOsSeriesReader(
        nodePort,
        object : WearOsLinkRetry {
            override suspend fun <T> run(pull: suspend () -> T): T = pull()
        },
    )

    private fun line(offset: Long, type: Type, value: Double, second: Double? = null, measured: Boolean = false) =
        WearLinkEntries.format(WearLinkEntry.of(t0 + offset, type, value, second, measured))

    private fun granted(vararg records: KClass<out Record>) {
        coEvery { healthConnect.grantedPermissions() } returns records.map { HealthPermission.getWritePermission(it) }.toSet()
    }

    @Test
    fun `the first pull asks for everything, and each page is written before the cursor moves`() = runTest {
        granted(HydrationRecord::class, WeightRecord::class)
        pages += listOf(line(0, Type.WATER, 250.0), line(1, Type.WEIGHT, 72.4))
        pages += listOf(line(2, Type.WATER, 250.0))

        val outcome = sync.sync(WATCH, reader)!!

        assertEquals(listOf(WearLinkSince.afterTime(0), "s.1"), asked)
        assertEquals(3, outcome.written)
        assertTrue(outcome.withheld.isEmpty())
        assertEquals("s.2", cursors.seriesCursor(WATCH.id, WearLinkProtocol.CAP_ENTRIES))
        assertEquals(setOf("wearos_entry_water_$t0@salt", "wearos_entry_weight_${t0 + 1}@salt", "wearos_entry_water_${t0 + 2}@salt"), store.stored.keys)
    }

    @Test
    fun `a withheld grant holds the cursor, but the granted types are still written, on every page`() = runTest {
        granted(HydrationRecord::class)
        pages += listOf(line(0, Type.WATER, 250.0), line(1, Type.BLOOD_PRESSURE, 121.0, 79.0))
        pages += listOf(line(2, Type.WATER, 250.0))

        val outcome = sync.sync(WATCH, reader)!!

        assertEquals(2, outcome.written)
        assertEquals(setOf(HealthPermission.getWritePermission(BloodPressureRecord::class)), outcome.withheld)
        assertEquals(2, asked.size)
        // The blood pressure comes again with the next sync; the water is not written twice.
        assertNull(cursors.seriesCursor(WATCH.id, WearLinkProtocol.CAP_ENTRIES))
        granted(HydrationRecord::class, BloodPressureRecord::class)
        val again = sync.sync(WATCH, reader)!!
        assertEquals(1, again.written)
        assertTrue(again.withheld.isEmpty())
        assertEquals("s.2", cursors.seriesCursor(WATCH.id, WearLinkProtocol.CAP_ENTRIES))
    }

    @Test
    fun `a breathing session the phone cannot keep, a refused value and an unknown type are dropped, and the cursor moves past them`() = runTest {
        granted(MindfulnessSessionRecord::class, WeightRecord::class)
        every { healthConnect.isMindfulnessAvailable() } returns false
        pages += listOf(
            line(0, Type.MINDFULNESS, 1.0),
            line(1, Type.WEIGHT, 900_000.0),
            "LG ${t0 + 2} coffee 1000 - h",
        )

        val outcome = sync.sync(WATCH, reader)!!

        assertEquals(0, outcome.written)
        assertTrue(outcome.withheld.isEmpty())
        assertEquals("s.1", cursors.seriesCursor(WATCH.id, WearLinkProtocol.CAP_ENTRIES))
        assertTrue(store.stored.isEmpty())
    }

    @Test
    fun `a breathing session is written where Health Connect keeps mindfulness`() = runTest {
        granted(MindfulnessSessionRecord::class)
        every { healthConnect.isMindfulnessAvailable() } returns true
        pages += listOf(line(60_000, Type.MINDFULNESS, 1.0))

        assertEquals(1, sync.sync(WATCH, reader)!!.written)
        assertTrue(store.stored.values.single() is MindfulnessSessionRecord)
    }

    /** Health Connect in memory, as the platform behaves: a stored id at its version or newer is kept. */
    private class MemoryStore : HealthRecordStore {
        val stored = linkedMapOf<String, Record>()

        override suspend fun insertImportedRecords(records: List<Record>) {
            for (record in records) stored[record.metadata.clientRecordId!!] = record
        }

        override suspend fun deleteImportedRecordsByClientIds(recordType: KClass<out Record>, clientRecordIds: List<String>) {
            clientRecordIds.forEach(stored::remove)
        }

        override suspend fun ownClientRecordVersions(recordType: KClass<out Record>, start: Instant, end: Instant): Map<String, Long> =
            stored.filterValues(recordType::isInstance).mapValues { it.value.metadata.clientRecordVersion }
    }

    private companion object {
        val WATCH = BleSensorDevice(
            id = "watch-1",
            displayName = "Galaxy Watch8",
            address = "AA:BB:CC:DD:EE:FF",
            bluetoothName = "Galaxy Watch8 (89FZ)",
            capabilities = emptySet(),
            enabled = true,
            wheelCircumferenceMm = null,
            addedAt = Instant.parse("2026-01-01T00:00:00Z"),
            kind = BleDeviceKind.WATCH,
            integration = DeviceIntegration.WEAROS,
            idSalt = "salt",
        )
    }
}
