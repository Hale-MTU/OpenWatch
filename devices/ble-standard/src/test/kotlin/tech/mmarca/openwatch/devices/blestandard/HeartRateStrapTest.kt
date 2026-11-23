package tech.mmarca.openwatch.devices.blestandard

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Device
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.data.repository.FakeHealthRecordStore
import tech.mmarca.openwatch.data.repository.contract.FakeDeviceRegistry
import tech.mmarca.openwatch.data.repository.contract.FakeDeviceStateRepository
import tech.mmarca.openwatch.devices.core.DeviceFeature
import tech.mmarca.openwatch.devices.core.DevicePairingResult
import tech.mmarca.openwatch.devices.core.LiveReading
import tech.mmarca.openwatch.devices.core.LiveReadingKind
import tech.mmarca.openwatch.devices.core.RadioLeaseOwner
import tech.mmarca.openwatch.devices.core.measure.DeviceMeasurementSink
import tech.mmarca.openwatch.devices.core.pairing.FakeWatchPairing
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleDiscoveredDevice
import tech.mmarca.openwatch.domain.model.BleSensorCapability
import tech.mmarca.openwatch.healthconnect.HealthConnectReads

/**
 * A heart rate strap from the add flow to Health Connect, over a fake radio: it pairs, is a
 * sensor that streams its heart rate, and what it streams is written through the record sink,
 * one record per clock minute.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeartRateStrapTest {

    private val registry = FakeDeviceRegistry()
    private val state = FakeDeviceStateRepository()
    private val store = FakeHealthRecordStore()
    private val radio = FakeProfileRadio().apply {
        has(StandardProfiles.HEART_RATE_SERVICE, StandardProfiles.HEART_RATE_MEASUREMENT)
        has(StandardProfiles.BATTERY_SERVICE, StandardProfiles.BATTERY_LEVEL)
        reads[StandardProfiles.BATTERY_LEVEL] = FakeProfileRadio.bytes(85)
    }
    private val reads = mockk<HealthConnectReads> {
        coEvery { grantedPermissions() } returns setOf(HealthPermission.getWritePermission(HeartRateRecord::class))
    }

    /** The phone's clock: a minute's start, then whatever the test moves it to. */
    private var clock = Instant.parse("2026-11-23T08:15:00Z")

    private val strap = BleDiscoveredDevice(
        address = "C8:2E:47:10:5A:3B",
        name = "HRM-Pro",
        rssi = -60,
        suggestedCapabilities = setOf(BleSensorCapability.HEART_RATE),
        advertisedServiceUuids = setOf("0000180d-0000-1000-8000-00805f9b34fb"),
    )

    private fun TestScope.family(scope: CoroutineScope = backgroundScope): StandardDeviceFamily {
        val sink = DeviceMeasurementSink(
            registry, state, store, reads, mapOf(StandardProfiles.FAMILY_ID to StandardMeasurementRecords()),
        ) { clock }
        val stream = HeartRateStream(radio, sink, registry, scope, { clock }, { ZoneId.of("Europe/Madrid") })
        val collector = MeasurementCollector(radio, sink, registry, state, scope, { clock }, { ZoneId.of("Europe/Madrid") })
        return StandardDeviceFamily(registry, state, stream, FakeWatchPairing(), collector)
    }

    @Test
    fun `a device that advertises the Heart Rate Service is offered as a sensor, and pairs with no bond`() = runTest {
        val family = family()

        assertEquals(BleDeviceKind.SENSOR, family.claim(strap))
        assertNull(family.claim(strap.copy(advertisedServiceUuids = setOf("00001816-0000-1000-8000-00805f9b34fb"))))
        assertTrue("0000180d-0000-1000-8000-00805f9b34fb" in family.scanServiceUuids)

        val result = family.pair(strap, "Chest strap")

        val device = registry.devices.single()
        assertEquals(DevicePairingResult.Paired(device.id), result)
        assertEquals(BleDeviceKind.SENSOR, device.kind)
        assertEquals(StandardProfiles.INTEGRATION, device.integration)
        assertEquals(setOf(BleSensorCapability.HEART_RATE), device.capabilities)
        assertTrue(family.owns(device))
        assertEquals(setOf(DeviceFeature.LIVE_HEART_RATE), family.features(device))
        // Nothing was dialled, bonded or associated to pair it.
        assertTrue(radio.links.isEmpty())
    }

    @Test
    fun `the stream is live readings while collected, and each clock minute reaches Health Connect`() = runTest(UnconfinedTestDispatcher()) {
        val family = family()
        family.pair(strap, "Chest strap")
        val device = registry.devices.single()
        val seen = mutableListOf<LiveReading>()

        val collecting = backgroundScope.launch { family.liveReadings(device).collect { seen += it } }
        val link = radio.links.single()
        assertEquals(RadioLeaseOwner.LIVE, link.owner)
        assertEquals(listOf(StandardProfiles.HEART_RATE_MEASUREMENT to false), link.enabled)
        assertEquals(85, registry.devices.single().batteryPercent)

        radio.send(StandardProfiles.HEART_RATE_MEASUREMENT, 0x06, 72)
        clock = clock.plusSeconds(1)
        // Off the skin: no reading, nothing written.
        radio.send(StandardProfiles.HEART_RATE_MEASUREMENT, 0x04, 0)
        clock = clock.plusSeconds(1)
        radio.send(StandardProfiles.HEART_RATE_MEASUREMENT, 0x16, 74, 0x00, 0x04)
        assertEquals(listOf(72.0, 74.0), seen.map { it.value })
        assertTrue(seen.all { it.kind == LiveReadingKind.HEART_RATE })
        assertTrue("a minute under way is not written yet", store.stored.isEmpty())

        clock = Instant.parse("2026-11-23T08:16:00Z")
        radio.send(StandardProfiles.HEART_RATE_MEASUREMENT, 0x00, 80)

        val firstMinute = store.stored.getValue("ble_standard_hr_${Instant.parse("2026-11-23T08:15:00Z").toEpochMilli()}") as HeartRateRecord
        assertEquals(listOf(72L, 74L), firstMinute.samples.map { it.beatsPerMinute })
        assertEquals(Instant.parse("2026-11-23T08:15:00Z"), firstMinute.startTime)
        assertEquals(Instant.parse("2026-11-23T08:15:02Z"), firstMinute.endTime)
        assertEquals(ZoneOffset.ofHours(1), firstMinute.startZoneOffset)
        assertEquals(Device.TYPE_CHEST_STRAP, firstMinute.metadata.device?.type)
        assertTrue(state.keys(device.id).isEmpty())

        // The last collector goes: after the grace the link closes and the minute under way is written.
        collecting.cancel()
        advanceTimeBy(6_000)
        assertTrue(link.closed)
        val lastMinute = store.stored.getValue("ble_standard_hr_${Instant.parse("2026-11-23T08:16:00Z").toEpochMilli()}") as HeartRateRecord
        assertEquals(listOf(80L), lastMinute.samples.map { it.beatsPerMinute })
        assertEquals(false, family.connections.first()[device.id])
    }

    @Test
    fun `two collectors share one link, and a link that drops is dialled again while collected`() = runTest(UnconfinedTestDispatcher()) {
        val family = family()
        family.pair(strap, "Chest strap")
        val device = registry.devices.single()

        val screen = backgroundScope.launch { family.liveReadings(device).collect {} }
        val app = backgroundScope.launch { family.liveReadings(device).collect {} }
        assertEquals(1, radio.links.size)

        radio.drop()
        advanceTimeBy(9_000)
        assertEquals(1, radio.links.size)
        advanceTimeBy(2_000)
        assertEquals(2, radio.links.size)

        screen.cancel()
        app.cancel()
    }

    @Test
    fun `a second strap's records are its own`() = runTest(UnconfinedTestDispatcher()) {
        val family = family()
        family.pair(strap, "First")
        family.pair(strap.copy(address = "C8:2E:47:10:5A:3C"), "Second")
        val second = registry.devices.last()
        assertTrue(second.idSalt.isNotEmpty())

        val collecting = backgroundScope.launch { family.liveReadings(second).collect {} }
        radio.send(StandardProfiles.HEART_RATE_MEASUREMENT, 0x00, 65)
        collecting.cancel()
        advanceTimeBy(6_000)

        assertEquals(listOf("ble_standard_hr_${clock.toEpochMilli()}@${second.idSalt}"), store.stored.keys.toList())
    }
}
