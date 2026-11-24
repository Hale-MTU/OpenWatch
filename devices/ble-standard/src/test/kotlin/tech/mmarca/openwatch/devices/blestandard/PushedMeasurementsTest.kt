package tech.mmarca.openwatch.devices.blestandard

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.data.repository.FakeHealthRecordStore
import tech.mmarca.openwatch.data.repository.contract.FakeDeviceRegistry
import tech.mmarca.openwatch.data.repository.contract.FakeDeviceStateRepository
import tech.mmarca.openwatch.devices.core.DevicePairingResult
import tech.mmarca.openwatch.devices.core.PairingFailure
import tech.mmarca.openwatch.devices.core.RadioLeaseOwner
import tech.mmarca.openwatch.devices.core.measure.DeviceMeasurementSink
import tech.mmarca.openwatch.devices.core.pairing.FakeWatchPairing
import tech.mmarca.openwatch.devices.core.pairing.WatchBondResult
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleDiscoveredDevice
import tech.mmarca.openwatch.healthconnect.HealthConnectReads

/**
 * A blood pressure monitor, a weight scale and a glucose meter from the add flow to Health
 * Connect, over a fake radio: each is bonded and associated, read when it wakes, and what it
 * hands over is kept and written through the record sink, as the specification's bytes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PushedMeasurementsTest {

    private val registry = FakeDeviceRegistry()
    private val state = FakeDeviceStateRepository()
    private val store = FakeHealthRecordStore()
    private val pairing = FakeWatchPairing()
    private val radio = FakeProfileRadio()
    private var granted = setOf(BloodPressureRecord::class, HeartRateRecord::class, WeightRecord::class, BloodGlucoseRecord::class)
        .map { HealthPermission.getWritePermission(it) }.toSet()
    private val reads = mockk<HealthConnectReads> { coEvery { grantedPermissions() } answers { granted } }

    /** The phone's clock when the device hands over: after every stamp below. */
    private val clock = Instant.parse("2026-11-23T09:00:00Z")
    private val zone = ZoneId.of("Europe/Madrid")

    private fun TestScope.family(): Pair<StandardDeviceFamily, MeasurementCollector> {
        val sink = DeviceMeasurementSink(
            registry, state, store, reads, mapOf(StandardProfiles.FAMILY_ID to StandardMeasurementRecords()),
        ) { clock }
        val collector = MeasurementCollector(radio, sink, registry, state, backgroundScope, { clock }, { zone })
        val stream = HeartRateStream(radio, sink, registry, backgroundScope, { clock }, { zone })
        return StandardDeviceFamily(registry, state, stream, pairing, collector) to collector
    }

    private fun scanned(vararg services: Int) = BleDiscoveredDevice(
        address = "C8:2E:47:10:5A:3B",
        name = "Device",
        rssi = -60,
        suggestedCapabilities = emptySet(),
        advertisedServiceUuids = services.map { StandardProfiles.sigUuid(it).toString() }.toSet(),
    )

    @Test
    fun `each profile is claimed by the service it advertises, the measuring one before a heart rate`() = runTest {
        val (family, _) = family()

        assertEquals(StandardProfiles.BLOOD_PRESSURE_MONITOR, family.claim(scanned(0x1810, 0x180D)))
        assertEquals(StandardProfiles.GLUCOSE_METER, family.claim(scanned(0x1808)))
        assertEquals(BleDeviceKind.SCALE, family.claim(scanned(0x181D)))
        assertEquals(BleDeviceKind.SENSOR, family.claim(scanned(0x180D)))
        assertNull(family.claim(scanned(0x1816)))
        assertEquals(
            listOf(0x180D, 0x1810, 0x181D, 0x1808).map { StandardProfiles.sigUuid(it).toString() }.toSet(),
            family.scanServiceUuids,
        )
    }

    @Test
    fun `a cuff is bonded and associated, read once at pairing, and its readings reach Health Connect`() = runTest {
        val (family, _) = family()
        radio.has(StandardProfiles.BLOOD_PRESSURE_SERVICE, StandardProfiles.BLOOD_PRESSURE_MEASUREMENT)
        // Two stored readings, indicated once the measurement is switched on: user 1 at 08:30, then 08:45.
        radio.onEnable[StandardProfiles.BLOOD_PRESSURE_MEASUREMENT] = listOf(
            FakeProfileRadio.value(StandardProfiles.BLOOD_PRESSURE_MEASUREMENT, 0x0E, 0x7A, 0x00, 0x50, 0x00, 0x5E, 0x00, *at(8, 30), 0x48, 0x00, 0x01),
            FakeProfileRadio.value(StandardProfiles.BLOOD_PRESSURE_MEASUREMENT, 0x0E, 0x82, 0x00, 0x55, 0x00, 0x64, 0x00, *at(8, 45), 0x4B, 0x00, 0x01),
        )

        val result = family.pair(scanned(0x1810), "Cuff")
        // The collection runs on the app's scope; the cuff then falls quiet.
        testScheduler.advanceTimeBy(30_000)
        testScheduler.runCurrent()

        val cuff = registry.devices.single()
        assertEquals(DevicePairingResult.Paired(cuff.id), result)
        assertEquals(StandardProfiles.BLOOD_PRESSURE_MONITOR, cuff.kind)
        assertEquals(listOf("bond:${cuff.address}", "associate:${cuff.address}"), pairing.calls)
        assertEquals(RadioLeaseOwner.SYNC, radio.links.single().owner)
        assertEquals(listOf(StandardProfiles.BLOOD_PRESSURE_MEASUREMENT to true), radio.links.single().enabled)

        val first = store.stored.getValue("ble_standard_bp_1_20261123083000") as BloodPressureRecord
        assertEquals(122.0, first.systolic.inMillimetersOfMercury, 0.0)
        assertEquals(80.0, first.diastolic.inMillimetersOfMercury, 0.0)
        // The cuff's own wall clock, read in the phone's offset.
        assertEquals(Instant.parse("2026-11-23T07:30:00Z"), first.time)
        assertEquals(ZoneOffset.ofHours(1), first.zoneOffset)
        val pulse = store.stored.getValue("ble_standard_bp_1_20261123083000_pulse") as HeartRateRecord
        assertEquals(72L, pulse.samples.single().beatsPerMinute)
        assertTrue(store.stored.containsKey("ble_standard_bp_1_20261123084500"))
        assertTrue(state.keys(cuff.id) == setOf(MeasurementCollector.KEY_USER))
        assertEquals(clock, registry.devices.single().lastSyncedAt)
    }

    @Test
    fun `a cuff's reading of another user is someone else's, and is left out`() = runTest {
        val (family, collector) = family()
        val cuff = registry.addDevice("Cuff", "C8:2E:47:10:5A:3B", null, emptySet(), kind = StandardProfiles.BLOOD_PRESSURE_MONITOR, integration = StandardProfiles.INTEGRATION)
        radio.has(StandardProfiles.BLOOD_PRESSURE_SERVICE, StandardProfiles.BLOOD_PRESSURE_MEASUREMENT)
        radio.onEnable[StandardProfiles.BLOOD_PRESSURE_MEASUREMENT] = listOf(
            FakeProfileRadio.value(StandardProfiles.BLOOD_PRESSURE_MEASUREMENT, 0x0A, 0x7A, 0x00, 0x50, 0x00, 0x5E, 0x00, *at(8, 30), 0x01),
            FakeProfileRadio.value(StandardProfiles.BLOOD_PRESSURE_MEASUREMENT, 0x0A, 0x8C, 0x00, 0x5A, 0x00, 0x6E, 0x00, *at(8, 40), 0x02),
            // An unknown user (0xFF) is anyone's.
            FakeProfileRadio.value(StandardProfiles.BLOOD_PRESSURE_MEASUREMENT, 0x0A, 0x78, 0x00, 0x4E, 0x00, 0x5C, 0x00, *at(8, 50), 0xFF),
        )

        collector.collectNow(cuff)

        assertEquals(setOf("ble_standard_bp_1_20261123083000", "ble_standard_bp_255_20261123085000"), store.stored.keys)
        assertTrue(family.owns(cuff))
    }

    @Test
    fun `a reading that waits for its grant is written once the grant comes, and one sent again is one record`() = runTest {
        val (_, collector) = family()
        granted = emptySet()
        val scale = registry.addDevice("Scale", "C8:2E:47:10:5A:3B", null, emptySet(), kind = BleDeviceKind.SCALE, integration = StandardProfiles.INTEGRATION)
        radio.has(StandardProfiles.WEIGHT_SCALE_SERVICE, StandardProfiles.WEIGHT_MEASUREMENT)
        radio.onEnable[StandardProfiles.WEIGHT_MEASUREMENT] = listOf(
            FakeProfileRadio.value(StandardProfiles.WEIGHT_MEASUREMENT, 0x02, 0x7C, 0x38, *at(8, 30)),
        )

        val waiting = collector.collectNow(scale)

        assertEquals(1, waiting.pending)
        assertTrue(store.stored.isEmpty())

        granted = setOf(HealthPermission.getWritePermission(WeightRecord::class))
        collector.collectNow(scale)

        val weight = store.stored.values.single() as WeightRecord
        assertEquals("ble_standard_weight_u_20261123083000", weight.metadata.clientRecordId)
        assertEquals(72.3, weight.weight.inKilograms, 1e-9)
        assertEquals(Device.TYPE_SCALE, weight.metadata.device?.type)
        assertTrue(state.keys(scale.id).isEmpty())
    }

    @Test
    fun `a meter is asked for its records, then only for the ones after the last it gave`() = runTest {
        val (_, collector) = family()
        val meter = registry.addDevice("Meter", "C8:2E:47:10:5A:3B", null, emptySet(), kind = StandardProfiles.GLUCOSE_METER, integration = StandardProfiles.INTEGRATION)
        radio.has(
            StandardProfiles.GLUCOSE_SERVICE,
            StandardProfiles.GLUCOSE_MEASUREMENT,
            StandardProfiles.GLUCOSE_CONTEXT,
            StandardProfiles.RECORD_ACCESS_CONTROL_POINT,
        )
        radio.onWrite = { _, request ->
            val from = if (request.size == 5) (request[3].toInt() and 0xFF) or ((request[4].toInt() and 0xFF) shl 8) else 0
            listOf(
                // Record 7: 95 mg/dL of capillary blood at 08:30 minus 5 minutes, after a meal (context).
                FakeProfileRadio.value(StandardProfiles.GLUCOSE_MEASUREMENT, 0x13, 0x07, 0x00, *at(8, 30), 0xFB, 0xFF, 0x5F, 0xB0, 0x11),
                FakeProfileRadio.value(StandardProfiles.GLUCOSE_CONTEXT, 0x83, 0x07, 0x00, 0x00, 0x01, 0x32, 0xD0, 0x02),
                // Record 8: a control solution check.
                FakeProfileRadio.value(StandardProfiles.GLUCOSE_MEASUREMENT, 0x02, 0x08, 0x00, *at(8, 40), 0x5F, 0xB0, 0x4A),
                // Record 9: 6.1 mmol/L of plasma at 08:50.
                FakeProfileRadio.value(StandardProfiles.GLUCOSE_MEASUREMENT, 0x06, 0x09, 0x00, *at(8, 50), 0x3D, 0xC0, 0x12),
            ).filter { GlucoseMeasurement.parse(it.bytes)?.sequenceNumber?.let { sequence -> sequence >= from } ?: true } +
                FakeProfileRadio.value(StandardProfiles.RECORD_ACCESS_CONTROL_POINT, 0x06, 0x00, 0x01, 0x01)
        }

        collector.collectNow(meter)

        val link = radio.links.single()
        assertEquals(
            listOf(
                StandardProfiles.GLUCOSE_MEASUREMENT to false,
                StandardProfiles.GLUCOSE_CONTEXT to false,
                StandardProfiles.RECORD_ACCESS_CONTROL_POINT to true,
            ),
            link.enabled,
        )
        assertArrayEquals(RecordAccessControlPoint.reportAll(), link.written.single().second)
        val seven = store.stored.getValue("ble_standard_glucose_7_20261123083000") as BloodGlucoseRecord
        assertEquals(5.273, seven.level.inMillimolesPerLiter, 0.001)
        assertEquals(Instant.parse("2026-11-23T07:25:00Z"), seven.time)
        assertEquals(BloodGlucoseRecord.SPECIMEN_SOURCE_CAPILLARY_BLOOD, seven.specimenSource)
        assertEquals(BloodGlucoseRecord.RELATION_TO_MEAL_AFTER_MEAL, seven.relationToMeal)
        assertEquals(MealType.MEAL_TYPE_BREAKFAST, seven.mealType)
        val nine = store.stored.getValue("ble_standard_glucose_9_20261123085000") as BloodGlucoseRecord
        assertEquals(6.1, nine.level.inMillimolesPerLiter, 1e-9)
        assertEquals(BloodGlucoseRecord.SPECIMEN_SOURCE_PLASMA, nine.specimenSource)
        assertEquals(2, store.stored.size)
        assertEquals("9", state.get(meter.id, MeasurementCollector.KEY_GLUCOSE_SEQUENCE))

        collector.collectNow(meter)

        assertArrayEquals(RecordAccessControlPoint.reportFrom(10), radio.links.last().written.single().second)
        assertEquals(2, store.stored.size)
    }

    @Test
    fun `a device that hangs up mid-way keeps what it sent, and a refused association pairs nothing`() = runTest {
        val (family, collector) = family()
        val scale = registry.addDevice("Scale", "C8:2E:47:10:5A:3B", null, emptySet(), kind = BleDeviceKind.SCALE, integration = StandardProfiles.INTEGRATION)
        radio.has(StandardProfiles.WEIGHT_SCALE_SERVICE, StandardProfiles.WEIGHT_MEASUREMENT)
        radio.onEnable[StandardProfiles.WEIGHT_MEASUREMENT] = listOf(
            FakeProfileRadio.value(StandardProfiles.WEIGHT_MEASUREMENT, 0x02, 0x7C, 0x38, *at(8, 30)),
        )
        radio.onWrite = { _, _ -> emptyList() }
        // The scale indicates its weigh-in and hangs up at once.
        val hangsUp = object : ProfileRadio {
            override suspend fun <T> withLink(address: String, owner: String, body: suspend (ProfileLink) -> T): T =
                radio.withLink(address, owner) { link ->
                    body(
                        object : ProfileLink by link {
                            override suspend fun enable(service: UUID, characteristic: UUID, indicate: Boolean) {
                                link.enable(service, characteristic, indicate)
                                radio.drop()
                            }
                        },
                    )
                }
        }
        val sink = DeviceMeasurementSink(registry, state, store, reads, mapOf(StandardProfiles.FAMILY_ID to StandardMeasurementRecords())) { clock }
        MeasurementCollector(hangsUp, sink, registry, state, backgroundScope, { clock }, { zone }).collectNow(scale)
        assertEquals(1, store.stored.size)

        registry.removeDevice(scale.id)
        pairing.associateResult = false
        assertEquals(DevicePairingResult.Failed(PairingFailure.NOT_ASSOCIATED), family.pair(scanned(0x181D), "Scale"))
        assertTrue(registry.devices.isEmpty())
        // The bond the pairing made goes with it.
        assertEquals("removeBond:C8:2E:47:10:5A:3B", pairing.calls.last())
        pairing.bondResult = WatchBondResult.REFUSED
        assertEquals(DevicePairingResult.Failed(PairingFailure.BOND_REFUSED), family.pair(scanned(0x181D), "Scale"))
    }

    /** A Date Time of 2026-11-23 at [hours]:[minutes]:00. */
    private fun at(hours: Int, minutes: Int) = intArrayOf(0xEA, 0x07, 0x0B, 0x17, hours, minutes, 0x00)
}
