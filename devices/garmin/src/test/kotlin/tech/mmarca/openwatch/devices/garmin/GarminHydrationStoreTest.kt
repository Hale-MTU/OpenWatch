package tech.mmarca.openwatch.devices.garmin

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.data.repository.FakeHealthRecordStore
import tech.mmarca.openwatch.data.repository.contract.FakeDeviceRegistry
import tech.mmarca.openwatch.data.repository.contract.FakeDeviceStateRepository
import tech.mmarca.openwatch.devices.core.DailyGoal
import tech.mmarca.openwatch.devices.core.measure.DeviceMeasurementSink
import tech.mmarca.openwatch.devices.core.measure.MeasurementSink
import tech.mmarca.openwatch.devices.core.measure.PushedMeasurement
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration
import tech.mmarca.openwatch.healthconnect.HealthConnectReads

/**
 * The hydration widget's drinks on the phone: kept by day for the widget's totals, handed to the
 * measurement sink as one record each, and taken back by a subtraction, newest first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GarminHydrationStoreTest {

    private val madrid = ZoneId.of("Europe/Madrid")
    private val watch = BleSensorDevice(
        id = "fenix",
        displayName = "fēnix 6X",
        address = "AA:BB:CC:DD:EE:01",
        bluetoothName = "fenix 6X Pro",
        capabilities = emptySet(),
        enabled = true,
        wheelCircumferenceMm = null,
        addedAt = Instant.parse("2026-07-01T10:00:00Z"),
        kind = BleDeviceKind.WATCH,
        integration = DeviceIntegration.GARMIN,
        idSalt = "s2",
    )
    private val state = FakeDeviceStateRepository()
    private val registry = FakeDeviceRegistry(listOf(watch))
    private val sink = RecordingMeasurementSink()
    private val goals = FakeSharedGoals()
    private var today = LocalDate.parse("2026-11-28")

    private fun TestScope.store(sink: MeasurementSink = this@GarminHydrationStoreTest.sink) =
        GarminHydrationStore(state, registry, sink, goals, backgroundScope, zone = { madrid }, today = { today })

    private fun at(text: String) = LocalDateTime.parse(text)

    private fun handed(): List<GarminHydrationRecords.Drink> =
        sink.accepted.flatMap { it.second }.map { GarminHydrationRecords.Drink.decode(it.payload)!! }

    @Test
    fun `a drink answers the day's total and goes to the sink at its time in the phone's zone`() = runTest {
        val log = store().log(watch.id)

        log.log(GarminHydrationEntry(at("2026-11-28T09:30:00"), 250.0))
        log.log(GarminHydrationEntry(at("2026-11-28T11:00:00"), 500.0))
        runCurrent()

        assertEquals(GarminHydrationDay(750.0, at("2026-11-28T11:00:00")), log.day(LocalDate.parse("2026-11-28")))
        assertEquals(GarminHydrationDay(0.0, null), log.day(LocalDate.parse("2026-11-27")))
        val first = sink.accepted.first().second.single()
        // 09:30 in Madrid in November is 08:30 UTC.
        assertEquals(GarminHydrationRecords.KEY_PREFIX + Instant.parse("2026-11-28T08:30:00Z").toEpochMilli(), first.key)
        assertEquals(listOf(250.0, 500.0), handed().map { it.milliliters })
        assertEquals(3600, handed().first().offsetSeconds)
    }

    @Test
    fun `the widget's goal is the water goal the apps share, two litres while there is none`() = runTest {
        val log = store().log(watch.id)
        val date = LocalDate.parse("2026-11-28")
        assertEquals(GarminHydrationDay.DEFAULT_GOAL_MILLILITERS, log.day(date).goalMilliliters, 0.0)

        goals.set(DailyGoal.WATER_ML, 2_600)
        assertEquals(2_600.0, log.day(date).goalMilliliters, 0.0)

        goals.set(DailyGoal.WATER_ML, null)
        assertEquals(GarminHydrationDay.DEFAULT_GOAL_MILLILITERS, log.day(date).goalMilliliters, 0.0)
    }

    @Test
    fun `the same entry sent again is kept once and handed over once`() = runTest {
        val log = store().log(watch.id)

        repeat(2) { log.log(GarminHydrationEntry(at("2026-11-28T09:30:00"), 250.0)) }
        runCurrent()

        assertEquals(250.0, log.day(LocalDate.parse("2026-11-28")).totalMilliliters, 0.0)
        assertEquals(1, sink.accepted.size)
    }

    @Test
    fun `a subtraction takes back the newest drink first and hands it over again with a higher version`() = runTest {
        val log = store().log(watch.id)
        log.log(GarminHydrationEntry(at("2026-11-28T09:00:00"), 500.0))
        log.log(GarminHydrationEntry(at("2026-11-28T10:00:00"), 250.0))
        runCurrent()
        sink.accepted.clear()

        log.log(GarminHydrationEntry(at("2026-11-28T10:05:00"), -400.0))
        runCurrent()

        assertEquals(350.0, log.day(LocalDate.parse("2026-11-28")).totalMilliliters, 0.0)
        // The 10:00 glass is gone and 150 of the 09:00 one; the subtraction itself is no record.
        assertEquals(listOf(350.0, 0.0), handed().map { it.milliliters })
        assertEquals(listOf(3L, 3L), handed().map { it.version })
    }

    @Test
    fun `a subtraction past every drink of the day leaves the day at zero`() {
        val left = GarminHydrationStore.netDrinks(
            listOf(
                GarminHydrationEntry(at("2026-11-28T09:00:00"), 300.0),
                GarminHydrationEntry(at("2026-11-28T09:10:00"), -500.0),
                GarminHydrationEntry(at("2026-11-28T12:00:00"), 200.0),
            ),
        )

        assertEquals(mapOf(at("2026-11-28T09:00:00") to 0.0, at("2026-11-28T12:00:00") to 200.0), left)
    }

    @Test
    fun `days past the kept fortnight are forgotten when the watch logs again`() = runTest {
        val log = store().log(watch.id)
        today = LocalDate.parse("2026-11-20")
        log.log(GarminHydrationEntry(at("2026-11-10T09:00:00"), 250.0))
        log.log(GarminHydrationEntry(at("2026-11-20T09:00:00"), 250.0))
        assertEquals(250.0, log.day(LocalDate.parse("2026-11-10")).totalMilliliters, 0.0)

        today = LocalDate.parse("2026-11-28")
        log.log(GarminHydrationEntry(at("2026-11-28T09:00:00"), 250.0))

        assertEquals(0.0, log.day(LocalDate.parse("2026-11-10")).totalMilliliters, 0.0)
        assertEquals(250.0, log.day(LocalDate.parse("2026-11-20")).totalMilliliters, 0.0)
    }

    @Test
    fun `the widget's settings are kept per watch`() = runTest {
        val store = store()

        store.log(watch.id).keepSettings("{\"hydrationMeasurementUnit\":\"ounce\"}")

        assertEquals("{\"hydrationMeasurementUnit\":\"ounce\"}", store.log(watch.id).settings())
        assertNull(store.log("another").settings())
    }

    @Test
    fun `a drink from a watch no longer registered answers the widget and goes nowhere`() = runTest {
        val log = store().log("removed")

        log.log(GarminHydrationEntry(at("2026-11-28T09:00:00"), 250.0))
        runCurrent()

        assertEquals(250.0, log.day(LocalDate.parse("2026-11-28")).totalMilliliters, 0.0)
        assertTrue(sink.accepted.isEmpty())
    }

    @Test
    fun `through the measurement sink a drink is one manual record, and a subtraction replaces it`() = runTest {
        val records = FakeHealthRecordStore()
        val reads = mockk<HealthConnectReads> {
            coEvery { grantedPermissions() } returns setOf(HealthPermission.getWritePermission(HydrationRecord::class))
        }
        val measurements = DeviceMeasurementSink(registry, state, records, reads, mapOf("GARMIN" to GarminHydrationRecords())) {
            Instant.parse("2026-11-28T12:00:00Z")
        }
        val log = store(measurements).log(watch.id)

        log.log(GarminHydrationEntry(at("2026-11-28T09:00:00"), 500.0))
        runCurrent()
        log.log(GarminHydrationEntry(at("2026-11-28T09:30:00"), -200.0))
        runCurrent()

        val record = records.stored.values.single() as HydrationRecord
        assertEquals("garmin_hydration_${Instant.parse("2026-11-28T08:00:00Z").toEpochMilli()}@s2", record.metadata.clientRecordId)
        assertEquals(300.0, record.volume.inMilliliters, 1e-9)
        assertEquals(2L, record.metadata.clientRecordVersion)
        assertEquals(Metadata.RECORDING_METHOD_MANUAL_ENTRY, record.metadata.recordingMethod)
        assertEquals(Device.TYPE_WATCH, record.metadata.device?.type)
        // Nothing is left waiting once it is written.
        assertTrue(state.keys(watch.id).none { it.startsWith(DeviceMeasurementSink.KEY_PREFIX) })
    }

    @Test
    fun `a kept drink this version cannot read, or another family's key, gives no record`() {
        val mapper = GarminHydrationRecords()

        assertTrue(mapper.records(watch, PushedMeasurement("garmin_hydration_1", "{}")).isEmpty())
        assertTrue(mapper.records(watch, PushedMeasurement("weight_1", "{\"at\":1,\"offset\":0,\"ml\":1,\"version\":1}")).isEmpty())
    }

    @Test
    fun `a drink a subtraction took back whole is still a record, of nothing, so the day's sum agrees`() {
        val drink = GarminHydrationRecords.Drink(atMillis = 1_764_316_800_000, offsetSeconds = 3600, milliliters = 0.0, version = 3)

        val record = GarminHydrationRecords().records(watch, PushedMeasurement("garmin_hydration_1764316800000", drink.encode())).single()

        assertEquals(0.0, (record as HydrationRecord).volume.inMilliliters, 0.0)
        assertEquals(3L, record.metadata.clientRecordVersion)
    }
}
