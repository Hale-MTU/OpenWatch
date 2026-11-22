package tech.mmarca.openwatch.devices.garmin.wellness

import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitWeightImportTest {

    @Test
    fun `weight scale message is decoded and mapped to Health Connect`() {
        val at = Instant.parse("2026-08-27T06:30:00Z")
        val data = FitW()
            .fileId(9)
            .def(
                local = 0,
                global = 30,
                fields = listOf(
                    listOf(253, 4, 134), // timestamp, uint32
                    listOf(0, 2, 132), // weight, uint16 scaled by 100
                ),
            )
            .u8(0)
            .u32(fitTimestamp(at))
            .u16(7_425)

        val reading = parseGarminWellness(fitWrap(data.toBytes())).weights.single()
        assertEquals(at, reading.time)
        assertEquals(74.25, reading.kilograms, 0.001)

        val record = fitWeightImportRecords(reading).single()
        assertTrue(record is WeightRecord)
        assertEquals(74.25, (record as WeightRecord).weight.inKilograms, 0.001)
    }

    @Test
    fun `a Garmin wellness record says a Garmin watch recorded it, not a hand on a phone`() {
        val metadata = importMetadata("garmin_fit_weight_1")

        assertEquals(Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED, metadata.recordingMethod)
        assertEquals(Device.TYPE_WATCH, metadata.device?.type)
        assertEquals("Garmin", metadata.device?.manufacturer)
        // The id scheme is untouched, so a record written again keeps its place.
        assertEquals("garmin_fit_weight_1", metadata.clientRecordId)
    }

    @Test
    fun `a calculating or invalid weight is skipped`() {
        val at = Instant.parse("2026-08-27T06:30:00Z")
        val data = FitW()
            .fileId(9)
            .def(
                local = 0,
                global = 30,
                fields = listOf(listOf(253, 4, 134), listOf(0, 2, 132)),
            )
            .u8(0).u32(fitTimestamp(at)).u16(0xFFFE) // still calculating
            .u8(0).u32(fitTimestamp(at)).u16(0xFFFF) // invalid

        assertTrue(parseGarminWellness(fitWrap(data.toBytes())).weights.isEmpty())
    }

    @Test
    fun `weight readings are only decoded from weight files`() {
        val at = Instant.parse("2026-08-27T06:30:00Z")
        val data = FitW()
            .fileId(32) // a monitoring file carrying a stray weight_scale row
            .def(
                local = 0,
                global = 30,
                fields = listOf(listOf(253, 4, 134), listOf(0, 2, 132)),
            )
            .u8(0).u32(fitTimestamp(at)).u16(7_425)

        assertTrue(parseGarminWellness(fitWrap(data.toBytes())).weights.isEmpty())
    }
}
