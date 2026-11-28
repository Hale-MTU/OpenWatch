package tech.mmarca.openwatch.wear

import android.hardware.SensorManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Pixel skin sensor's one pure branch: the point counts as measured at
 * medium accuracy or higher, the contract [SensorSkinTemperatureSource] reads
 * off each event's accuracy. The sensor itself needs a watch.
 */
class SensorSkinTemperatureSourceTest {

    @Test
    fun `a point counts as measured only at the sensor's medium accuracy or higher`() {
        // No ACCURACY_UNRELIABLE constant exists in the SDK; the tier below
        // ACCURACY_LOW is the bare SENSOR_STATUS_UNRELIABLE (0).
        assertFalse(SensorSkinTemperatureSource.isMeasuredSkinPoint(SensorManager.SENSOR_STATUS_UNRELIABLE))
        assertFalse(SensorSkinTemperatureSource.isMeasuredSkinPoint(SensorManager.SENSOR_STATUS_ACCURACY_LOW))
        assertTrue(SensorSkinTemperatureSource.isMeasuredSkinPoint(SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM))
        assertTrue(SensorSkinTemperatureSource.isMeasuredSkinPoint(SensorManager.SENSOR_STATUS_ACCURACY_HIGH))
    }

    @Test
    fun `the standing value handed at registration counts from then, unless it is too old to stand`() {
        val registered = 1_794_100_020_000L
        // An on-change sensor hands over its standing value: ten minutes old, it is today's value now.
        assertEquals(registered, SensorSkinTemperatureSource.pointTime(registered - 10 * 60_000, registered))
        // Older than a silence may last, it is not.
        assertNull(SensorSkinTemperatureSource.pointTime(registered - 2 * 60 * 60_000, registered))
        assertEquals(registered - 10_000, SensorSkinTemperatureSource.pointTime(registered - 10_000, registered))
        // A FIFO point delivered late, but taken after registering, keeps its own time.
        assertEquals(registered + 60_000, SensorSkinTemperatureSource.pointTime(registered + 60_000, registered))
    }
}
