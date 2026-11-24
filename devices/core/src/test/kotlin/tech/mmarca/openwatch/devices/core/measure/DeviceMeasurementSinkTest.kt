package tech.mmarca.openwatch.devices.core.measure

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.data.repository.FakeHealthRecordStore
import tech.mmarca.openwatch.data.repository.contract.FakeDeviceRegistry
import tech.mmarca.openwatch.data.repository.contract.FakeDeviceStateRepository
import tech.mmarca.openwatch.devices.core.saltedRecordId
import tech.mmarca.openwatch.devices.core.sync.SyncFailure
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration
import tech.mmarca.openwatch.healthconnect.HealthConnectReads
import tech.mmarca.openwatch.healthconnect.HealthConnectSyncDisabledException

class DeviceMeasurementSinkTest {

    private val registry = FakeDeviceRegistry()
    private val state = FakeDeviceStateRepository()
    private val store = FakeHealthRecordStore()
    private var granted = setOf(WriteWeight, WriteHeartRate)
    private val reads = mockk<HealthConnectReads> { coEvery { grantedPermissions() } answers { granted } }
    private val stamp = Instant.parse("2026-11-23T08:00:00Z")

    /** A family's mapping: "<kg>" a weight, "<kg>,<bpm>" a weight with a pulse, "x" nothing usable. */
    private val mapper = MeasurementRecords { device, measurement ->
        val parts = measurement.payload.split(',')
        val kg = parts[0].toDoubleOrNull() ?: return@MeasurementRecords emptyList()
        val time = Instant.parse("2026-11-23T07:00:00Z")
        buildList<Record> {
            add(WeightRecord(time, ZoneOffset.UTC, Mass.kilograms(kg), metadata("weight_${measurement.key}", device)))
            parts.getOrNull(1)?.toLong()?.let { bpm ->
                add(
                    HeartRateRecord(
                        time, ZoneOffset.UTC, time, ZoneOffset.UTC,
                        listOf(HeartRateRecord.Sample(time, bpm)),
                        metadata("pulse_${measurement.key}", device),
                    ),
                )
            }
        }
    }

    private val sink = DeviceMeasurementSink(registry, state, store, reads, mapOf("SOMEVENDOR" to mapper)) { stamp }

    private val device: BleSensorDevice = registry.addDevice(
        displayName = "Scale",
        address = "AA:BB:CC:DD:EE:01",
        bluetoothName = null,
        capabilities = emptySet(),
        kind = BleDeviceKind.SCALE,
        integration = DeviceIntegration("SOMEVENDOR"),
    )

    @Test
    fun `a measurement is written through the record sink, its device stamped, and nothing is kept`() = runTest {
        val outcome = sink.accept(device, listOf(PushedMeasurement("1", "72.5,61")))

        assertEquals(MeasurementOutcome(written = 1), outcome)
        assertEquals(setOf("weight_1", "pulse_1"), store.stored.keys)
        assertTrue(state.keys(device.id).isEmpty())
        assertEquals(stamp, registry.devices.single().lastSyncedAt)
        assertTrue(sink.backlog.value.isEmpty())
    }

    @Test
    fun `without its first record's grant a measurement is kept, and written once the grant comes`() = runTest {
        granted = setOf(WriteHeartRate)

        val first = sink.accept(device, listOf(PushedMeasurement("1", "72.5,61")))

        assertEquals(MeasurementOutcome(pending = 1, withheld = setOf(WriteWeight)), first)
        assertTrue(store.stored.isEmpty())
        assertEquals(setOf("measurement.1"), state.keys(device.id))
        assertEquals(first, sink.backlog.value[device.id])

        granted = setOf(WriteWeight, WriteHeartRate)
        assertEquals(MeasurementOutcome(written = 1), sink.retryPending())
        assertEquals(setOf("weight_1", "pulse_1"), store.stored.keys)
        assertNull(sink.backlog.value[device.id])
    }

    @Test
    fun `a record whose grant alone is missing is left out, and the measurement is done`() = runTest {
        granted = setOf(WriteWeight)

        sink.accept(device, listOf(PushedMeasurement("1", "72.5,61")))

        assertEquals(setOf("weight_1"), store.stored.keys)
        assertTrue(state.keys(device.id).isEmpty())
    }

    @Test
    fun `a failed write keeps every measurement, says why, and the next pass writes them`() = runTest {
        store.failure = { HealthConnectSyncDisabledException() }

        val outcome = sink.accept(device, listOf(PushedMeasurement("1", "70"), PushedMeasurement("2", "71")))

        assertEquals(MeasurementOutcome(pending = 2, failure = SyncFailure.SyncPaused), outcome)
        assertEquals(setOf("measurement.1", "measurement.2"), state.keys(device.id))

        store.failure = { null }
        assertEquals(2, sink.accept(device, emptyList()).written)
        assertEquals(setOf("weight_1", "weight_2"), store.stored.keys)
    }

    @Test
    fun `a measurement that gives no record is dropped, and one handed over twice is written once`() = runTest {
        sink.accept(device, listOf(PushedMeasurement("1", "x")))
        assertTrue(state.keys(device.id).isEmpty())

        sink.accept(device, listOf(PushedMeasurement("2", "70")))
        sink.accept(device, listOf(PushedMeasurement("2", "70")))

        assertEquals(setOf("weight_2"), store.stored.keys)
    }

    @Test
    fun `a measurement of a family with no mapping is kept for a version that has one`() = runTest {
        val other = registry.addDevice("Cuff", "AA:BB:CC:DD:EE:02", null, emptySet(), integration = DeviceIntegration("NEWER"))

        val outcome = sink.accept(other, listOf(PushedMeasurement("1", "70")))

        assertEquals(MeasurementOutcome(pending = 1), outcome)
        assertEquals(setOf("measurement.1"), state.keys(other.id))
        assertTrue(store.stored.isEmpty())
    }

    private fun metadata(id: String, device: BleSensorDevice) =
        Metadata.autoRecorded(device = androidx.health.connect.client.records.metadata.Device(type = 0), clientRecordId = saltedRecordId(id, device.idSalt))

    private companion object {
        val WriteWeight = HealthPermission.getWritePermission(WeightRecord::class)
        val WriteHeartRate = HealthPermission.getWritePermission(HeartRateRecord::class)
    }
}
