package tech.mmarca.openwatch.devices.blestandard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Heart Rate Measurement (0x2A37) against the layout of the Heart Rate Service
 * specification: flags, then the value, the energy expended and the RR intervals in that order,
 * each only when its flag says so, little-endian.
 */
class HeartRateMeasurementTest {

    @Test
    fun `a uint8 value with no contact status, energy or intervals`() {
        val measurement = parse(0x00, 0x48)

        assertEquals(HeartRateMeasurement(72, SensorContact.NOT_SUPPORTED, null, emptyList()), measurement)
        assertTrue(measurement.isUsable)
    }

    @Test
    fun `bit 0 makes the value a uint16`() {
        assertEquals(300, parse(0x01, 0x2C, 0x01).beatsPerMinute)
    }

    @Test
    fun `bits 1 and 2 are the sensor contact, and a strap off the skin is no reading`() {
        assertEquals(SensorContact.DETECTED, parse(0x06, 0x48).sensorContact)
        val off = parse(0x04, 0x48)
        assertEquals(SensorContact.NOT_DETECTED, off.sensorContact)
        assertFalse(off.isUsable)
        // Bit 1 alone is "not supported", whatever it says.
        assertEquals(SensorContact.NOT_SUPPORTED, parse(0x02, 0x48).sensorContact)
        assertFalse(parse(0x00, 0x00).isUsable)
    }

    @Test
    fun `bit 3 adds the energy expended in kilojoules`() {
        val measurement = parse(0x08, 0x50, 0xE8, 0x03)

        assertEquals(80, measurement.beatsPerMinute)
        assertEquals(1000, measurement.energyExpendedKilojoules)
    }

    @Test
    fun `bit 4 adds RR intervals in 1024ths of a second, as many as fill the value`() {
        val measurement = parse(0x10, 0x3C, 0x00, 0x04, 0x00, 0x04)

        assertEquals(listOf(1024, 1024), measurement.rrIntervals1024)
        assertEquals(listOf(1000.0, 1000.0), measurement.rrIntervalsMillis)
    }

    @Test
    fun `every field at once, in the specification's order`() {
        // Contact detected, energy and RR present: 75 bpm, 10000 kJ, one interval of 973/1024 s.
        val measurement = parse(0x1E, 0x4B, 0x10, 0x27, 0xCD, 0x03)

        assertEquals(HeartRateMeasurement(75, SensorContact.DETECTED, 10_000, listOf(973)), measurement)
        assertEquals(950.195, measurement.rrIntervalsMillis.single(), 0.001)
    }

    @Test
    fun `a uint16 value with intervals reads the intervals after both value bytes`() {
        val measurement = parse(0x11, 0x5A, 0x00, 0x9A, 0x02, 0x66, 0x02)

        assertEquals(90, measurement.beatsPerMinute)
        assertEquals(listOf(666, 614), measurement.rrIntervals1024)
    }

    @Test
    fun `a value shorter than its flags say is no measurement, and a stray byte is no interval`() {
        assertNull(HeartRateMeasurement.parse(byteArrayOf()))
        assertNull(HeartRateMeasurement.parse(bytes(0x00)))
        assertNull(HeartRateMeasurement.parse(bytes(0x01, 0x2C)))
        assertNull(HeartRateMeasurement.parse(bytes(0x08, 0x50, 0xE8)))
        assertEquals(listOf(1024), parse(0x10, 0x3C, 0x00, 0x04, 0x01).rrIntervals1024)
        assertEquals(emptyList<Int>(), parse(0x10, 0x3C).rrIntervals1024)
    }

    private fun parse(vararg value: Int): HeartRateMeasurement = HeartRateMeasurement.parse(bytes(*value))!!

    private fun bytes(vararg value: Int) = ByteArray(value.size) { value[it].toByte() }
}
