package tech.mmarca.openwatch.devices.blestandard

import java.time.LocalDateTime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Blood Pressure, Weight Scale and Glucose profiles' measurements against the layouts their
 * specifications give: the flags first, the fields each flag adds in the specification's order,
 * little-endian, the values in IEEE 11073 SFLOAT where the profile says so.
 */
class HealthProfilesTest {

    // The SFLOAT and the Date Time the measurements embed.

    @Test
    fun `an SFLOAT is a 12-bit mantissa times ten to a 4-bit exponent, both signed`() {
        assertEquals(114.0, sfloat(0x0072), 0.0)
        // Exponent -1 (0xF), mantissa 160: 16.0, a pressure in kPa.
        assertEquals(16.0, sfloat(0xF0A0), 1e-9)
        // Exponent -5 (0xB), mantissa 95: 95e-5 kg/L, 95 mg/dL of glucose.
        assertEquals(95e-5, sfloat(0xB05F), 1e-12)
        // Exponent 2, mantissa -1 (0xFFF): -100.
        assertEquals(-100.0, sfloat(0x2FFF), 0.0)
        assertEquals(20470.0, sfloat(0x17FF), 0.0)
    }

    @Test
    fun `the five reserved SFLOAT words are no reading`() {
        for (word in listOf(0x07FF, 0x0800, 0x07FE, 0x0802, 0x0801)) assertTrue(sfloat(word).isNaN())
    }

    @Test
    fun `a date time is the device's wall clock, and one it does not know is none`() {
        val time = GattDateTime(2026, 11, 23, 8, 30, 15)

        assertEquals(LocalDateTime.of(2026, 11, 23, 8, 30, 15), time.toLocalDateTime())
        assertEquals("20261123083015", time.stamp)
        assertNull(time.copy(year = 0).toLocalDateTime())
        assertNull(time.copy(month = 2, day = 30).toLocalDateTime())
        assertNull(time.copy(hours = 25).toLocalDateTime())
    }

    // Blood Pressure Measurement, 0x2A35.

    @Test
    fun `a blood pressure measurement with every field, in mmHg`() {
        // Time stamp, pulse, user and status present: 122/80 (94), 2026-11-23 08:30:00, 72 bpm, user 1, irregular pulse.
        val reading = BloodPressureMeasurement.parse(
            bytes(0x1E, 0x7A, 0x00, 0x50, 0x00, 0x5E, 0x00, *DATE, 0x48, 0x00, 0x01, 0x04, 0x00),
        )!!

        assertEquals(122.0, reading.systolicMmHg, 0.0)
        assertEquals(80.0, reading.diastolicMmHg, 0.0)
        assertEquals(94.0, reading.meanArterialMmHg, 0.0)
        assertEquals(GattDateTime(2026, 11, 23, 8, 30, 0), reading.timeStamp)
        assertEquals(72.0, reading.pulseRate!!, 0.0)
        assertEquals(1, reading.userId)
        assertEquals(0x0004, reading.status)
        assertTrue(reading.isUsable)
    }

    @Test
    fun `a blood pressure measurement in kPa is in mmHg once read, and the optional fields are absent`() {
        // 16.0 / 10.7 / 12.5 kPa.
        val reading = BloodPressureMeasurement.parse(bytes(0x01, 0xA0, 0xF0, 0x6B, 0xF0, 0x7D, 0xF0))!!

        assertEquals(120.01, reading.systolicMmHg, 0.01)
        assertEquals(80.26, reading.diastolicMmHg, 0.01)
        assertNull(reading.timeStamp)
        assertNull(reading.pulseRate)
        assertNull(reading.userId)
    }

    @Test
    fun `a cuff that could not read a pressure sends a reserved value, and a short value is no measurement`() {
        assertFalse(BloodPressureMeasurement.parse(bytes(0x00, 0xFF, 0x07, 0xFF, 0x07, 0xFF, 0x07))!!.isUsable)
        assertNull(BloodPressureMeasurement.parse(bytes(0x00, 0x7A, 0x00, 0x50, 0x00)))
        assertNull(BloodPressureMeasurement.parse(bytes(0x02, 0x7A, 0x00, 0x50, 0x00, 0x5E, 0x00, 0xEA, 0x07)))
    }

    // Weight Measurement, 0x2A9D.

    @Test
    fun `a weight in SI is 0_005 kg a step, with its time stamp, user, BMI and height`() {
        // 14460 * 0.005 = 72.3 kg; BMI 236 * 0.1; height 1750 mm.
        val reading = WeightMeasurement.parse(bytes(0x0E, 0x7C, 0x38, *DATE, 0x02, 0xEC, 0x00, 0xD6, 0x06))!!

        assertEquals(72.3, reading.weightKg!!, 1e-9)
        assertEquals(GattDateTime(2026, 11, 23, 8, 30, 0), reading.timeStamp)
        assertEquals(2, reading.userId)
        assertEquals(23.6, reading.bmi!!, 1e-9)
        assertEquals(1.75, reading.heightMetres!!, 1e-9)
    }

    @Test
    fun `a weight in imperial units is 0_01 lb a step, read in kilograms`() {
        // 15000 * 0.01 lb = 150 lb.
        val reading = WeightMeasurement.parse(bytes(0x01, 0x98, 0x3A))!!

        assertEquals(68.0388555, reading.weightKg!!, 1e-6)
        assertNull(reading.timeStamp)
    }

    @Test
    fun `an unsuccessful weigh-in carries no weight, and a short value is no measurement`() {
        assertNull(WeightMeasurement.parse(bytes(0x00, 0xFF, 0xFF))!!.weightKg)
        assertNull(WeightMeasurement.parse(bytes(0x00, 0x7C)))
        assertNull(WeightMeasurement.parse(bytes(0x08, 0x7C, 0x38, 0xEC, 0x00)))
    }

    // Glucose Measurement, 0x2A18, and its context, 0x2A34.

    @Test
    fun `a glucose record in kg per litre, capillary whole blood from a finger`() {
        // Time offset, concentration and status present, context follows: sequence 7, offset -5
        // minutes, 95 mg/dL (95e-5 kg/L), type 1 at location 1, no status bits.
        val reading = GlucoseMeasurement.parse(
            bytes(0x1B, 0x07, 0x00, *DATE, 0xFB, 0xFF, 0x5F, 0xB0, 0x11, 0x00, 0x00),
        )!!

        assertEquals(7, reading.sequenceNumber)
        assertEquals(GattDateTime(2026, 11, 23, 8, 30, 0), reading.baseTime)
        assertEquals(-5, reading.timeOffsetMinutes)
        assertEquals(5.273, reading.mmolPerLiter!!, 0.001)
        assertEquals(GlucoseSampleType.CAPILLARY_WHOLE_BLOOD, reading.sampleType)
        assertEquals(1, reading.sampleLocation)
        assertEquals(0, reading.sensorStatus)
        assertTrue(reading.contextFollows)
    }

    @Test
    fun `a glucose record in mol per litre, with no offset`() {
        // 5.3 mmol/L is 0.0053 mol/L: mantissa 53, exponent -4 (0xC). Plasma (2) from a finger.
        val reading = GlucoseMeasurement.parse(bytes(0x06, 0x2A, 0x01, *DATE, 0x35, 0xC0, 0x12))!!

        assertEquals(298, reading.sequenceNumber)
        assertEquals(0, reading.timeOffsetMinutes)
        assertEquals(5.3, reading.mmolPerLiter!!, 1e-9)
        assertEquals(GlucoseSampleType.CAPILLARY_PLASMA, reading.sampleType)
        assertFalse(reading.contextFollows)
    }

    @Test
    fun `a record without a concentration, a control solution check, and a short value`() {
        assertNull(GlucoseMeasurement.parse(bytes(0x00, 0x01, 0x00, *DATE))!!.mmolPerLiter)
        assertEquals(
            GlucoseSampleType.CONTROL_SOLUTION,
            GlucoseMeasurement.parse(bytes(0x02, 0x01, 0x00, *DATE, 0x5F, 0xB0, 0x4A))!!.sampleType,
        )
        assertNull(GlucoseMeasurement.parse(bytes(0x00, 0x01, 0x00, 0xEA, 0x07)))
        assertNull(GlucoseMeasurement.parse(bytes(0x02, 0x01, 0x00, *DATE, 0x5F, 0xB0)))
    }

    @Test
    fun `a context's meal and carbohydrate, past the extended flags`() {
        // Carbohydrate, meal and extended flags present: sequence 7, extended 0, breakfast with
        // 50 g (5e-2 kg: mantissa 50, exponent -3), postprandial.
        val context = GlucoseContext.parse(bytes(0x83, 0x07, 0x00, 0x00, 0x01, 0x32, 0xD0, 0x02))!!

        assertEquals(GlucoseContext(7, 1, GlucoseMeal.POSTPRANDIAL), context)
        assertEquals(GlucoseContext(7, null, GlucoseMeal.FASTING), GlucoseContext.parse(bytes(0x02, 0x07, 0x00, 0x03)))
        assertNull(GlucoseContext.parse(bytes(0x02, 0x07, 0x00)))
    }

    // Record Access Control Point, 0x2A52.

    @Test
    fun `the control point asks for every record, or for those from a sequence number on`() {
        assertArrayEquals(bytes(0x01, 0x01), RecordAccessControlPoint.reportAll())
        assertArrayEquals(bytes(0x01, 0x03, 0x01, 0x34, 0x12), RecordAccessControlPoint.reportFrom(0x1234))
    }

    @Test
    fun `the meter's answer to a report request is its response code`() {
        assertEquals(RecordAccessControlPoint.RESPONSE_SUCCESS, RecordAccessControlPoint.reportResponse(bytes(0x06, 0x00, 0x01, 0x01)))
        assertEquals(RecordAccessControlPoint.RESPONSE_NO_RECORDS_FOUND, RecordAccessControlPoint.reportResponse(bytes(0x06, 0x00, 0x01, 0x06)))
        // The number of stored records (op code 5) is no answer to a report.
        assertNull(RecordAccessControlPoint.reportResponse(bytes(0x05, 0x00, 0x0A, 0x00)))
        assertNull(RecordAccessControlPoint.reportResponse(bytes(0x06, 0x00, 0x04, 0x01)))
    }

    // What the family keeps until Health Connect has it.

    @Test
    fun `a received value is kept as its bytes, with its time and offset, and read back as it was`() {
        val value = ReceivedValue(1_764_000_000_000, 3600, bytes(0x1E, 0x7A), context = bytes(0x02, 0x07))

        assertEquals("raw1;1764000000000;3600;1e7a;0207", value.encode())
        assertEquals(value, ReceivedValue.decode(value.encode()))
        assertEquals(ReceivedValue(1, 0, bytes(0xFF)), ReceivedValue.decode("raw1;1;0;ff;"))
        assertNull(ReceivedValue.decode("raw2;1;0;ff;"))
        assertNull(ReceivedValue.decode("raw1;1;0;f;"))
        assertNull(ReceivedValue.decode("raw1;1;0;;"))
    }

    private fun bytes(vararg value: Int) = ByteArray(value.size) { value[it].toByte() }

    private companion object {
        /** 2026-11-23 08:30:00 as a Date Time: year 2026 (0x07EA), November, 23, 8, 30, 0. */
        val DATE = intArrayOf(0xEA, 0x07, 0x0B, 0x17, 0x08, 0x1E, 0x00)
    }
}
