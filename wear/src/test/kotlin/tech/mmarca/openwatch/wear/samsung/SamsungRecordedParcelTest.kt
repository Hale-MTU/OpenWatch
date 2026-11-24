package tech.mmarca.openwatch.wear.samsung

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The platform's callbacks as recorded bytes ([RecordedCallbacks]), read by
 * the same parser the watch runs, in both layouts Android writes them in.
 */
class SamsungRecordedParcelTest {

    @Test
    fun `every recorded callback reads back as the points the watch sent`() {
        for (callback in RecordedCallbacks.ALL) {
            val points = SamsungHealthPlatform.readPoints(NativeParcelBytes(callback.bytes))

            assertEquals(1, points.size)
            assertEquals(callback.epochMillis, points.single().epochMillis)
            assertEquals(callback.ints, points.single().ints.toList())
        }
    }

    @Test
    fun `the recorded heart rate point decodes to its rate and its two intervals in either layout`() {
        for (callback in listOf(RecordedCallbacks.HEART_RATE_API_33, RecordedCallbacks.HEART_RATE_API_30)) {
            val point = SamsungHealthPlatform.readPoints(NativeParcelBytes(callback.bytes)).single().toHeartRatePoint()

            assertEquals(52, point.bpm)
            assertEquals(listOf(1169, 1125), point.intervalsMillis.toList())
        }
    }

    @Test
    fun `the recorded SpO2 point decodes to a completed measurement`() {
        val reading = SamsungHealthPlatform.readPoints(NativeParcelBytes(RecordedCallbacks.SPO2_API_33.bytes)).single().toSpo2Reading()

        assertEquals(100, reading.percent)
        assertEquals(51, reading.bpm)
    }

    @Test
    fun `the test writer lays a callback out exactly as recorded`() {
        val hr = RecordedCallbacks.HEART_RATE_API_33
        val points = listOf(hr.epochMillis to hr.ints.toIntArray())

        assertArrayEquals(hr.bytes, NativeCallbackWriter.callback(points, lengthPrefixed = true))
        assertArrayEquals(RecordedCallbacks.HEART_RATE_API_30.bytes, NativeCallbackWriter.callback(points, lengthPrefixed = false))
    }

    @Test
    fun `a first point of 535 intervals, as a Galaxy Watch7 sent after a start, reads whole`() {
        val pairs = 535
        val ints = IntArray(4 + 2 * pairs) { index ->
            when (index) {
                0 -> 56
                2 -> 1
                3 -> pairs
                else -> if (index >= 4 && (index - 4) % 2 == 0) 1_000 + (index - 4) / 2 % 200 else 0
            }
        }
        for (lengthPrefixed in listOf(true, false)) {
            val bytes = NativeCallbackWriter.callback(listOf(1_794_100_000_000L to ints), lengthPrefixed)

            val point = SamsungHealthPlatform.readPoints(NativeParcelBytes(bytes)).single().toHeartRatePoint()

            assertEquals(pairs, point.intervalsMillis.size)
            assertEquals(56, point.bpm)
        }
    }

    @Test
    fun `a count the bytes left cannot hold fails the read before it is allocated`() {
        val hr = RecordedCallbacks.HEART_RATE_API_33.bytes
        // The point claims 4 000 values, under the cap, in a parcel that holds eight.
        val claimed = hr.copyOf().also { java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(16, 4_000) }

        assertTrue(runCatching { SamsungHealthPlatform.readPoints(NativeParcelBytes(claimed)) }.exceptionOrNull() is IllegalStateException)

        // Fifty thousand points, under the cap, in a parcel of a few kilobytes.
        val many = hr.copyOf().also { java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(0, 50_000) }
        assertTrue(runCatching { SamsungHealthPlatform.readPoints(NativeParcelBytes(many)) }.exceptionOrNull() is IllegalStateException)
    }
}
