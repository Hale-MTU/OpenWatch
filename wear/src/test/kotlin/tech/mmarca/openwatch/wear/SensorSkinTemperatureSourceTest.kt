package tech.mmarca.openwatch.wear

import android.hardware.SensorManager
import org.junit.Assert.assertFalse
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
    fun `only a reading from well before the listener registered is the cached one`() {
        val registered = 1_794_100_020_000L
        assertTrue(SensorSkinTemperatureSource.isCachedReading(registered - 10 * 60_000, registered))
        assertFalse(SensorSkinTemperatureSource.isCachedReading(registered - 10_000, registered))
        // A FIFO point delivered late, but taken after registering, is kept however old it is now.
        assertFalse(SensorSkinTemperatureSource.isCachedReading(registered + 60_000, registered))
    }
}
