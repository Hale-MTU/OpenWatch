package tech.mmarca.openwatch.wear.samsung

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungPointsTest {

    private fun raw(vararg ints: Int, floats: FloatArray = FloatArray(ints.size)) = RawPoint(1_794_100_000_000L, ints, floats)

    @Test
    fun `an SpO2 reading has a value only once completed with a plausible one`() {
        // As the Galaxy Watch7 sent them: status, value, heart rate, accuracy.
        val done = raw(2, 96, 51, 0).toSpo2Reading()
        assertEquals(96, done.percent)
        assertEquals(51, done.bpm)
        assertNull(raw(0, 0, 0, 0).toSpo2Reading().percent)
        assertNull(raw(-6, 0, 0, 0).toSpo2Reading().percent)
        assertNull(raw(2, 40, 51, 0).toSpo2Reading().percent)
        assertTrue(raw(-4, 0, 0, 0).toSpo2Reading().moving)
        assertFalse(done.moving)
    }

    @Test
    fun `a heart rate point carries its rate and its interval pairs`() {
        val point = raw(52, 33937, 1, 2, 1169, 0, 1125, -1).toHeartRatePoint()
        assertTrue(point.hasHeartRate)
        assertEquals(52, point.bpm)
        assertEquals(listOf(1169, 1125), point.intervalsMillis.toList())
        assertEquals(listOf(0, -1), point.statuses.toList())
    }

    @Test
    fun `a skin reading takes the two temperatures from the floats and is measured at status 0`() {
        val reading = raw(0, 0, 0, floats = floatArrayOf(33.9f, 22.1f, 0f)).toSkinReading()
        assertEquals(33.9f, reading.skinCelsius, 1e-6f)
        assertEquals(22.1f, reading.ambientCelsius, 1e-6f)
        assertTrue(reading.measured)
        assertFalse(raw(0, 0, -1).toSkinReading().measured)
    }
}
