package tech.mmarca.openwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Test

/** The watch's one median, which the acceleration features and the PPG beats share. */
class AccelerationMinuteFeaturesMedianTest {

    @Test
    fun `an odd count takes the middle value`() {
        assertEquals(3.0, AccelerationMinuteFeatures.median(listOf(5.0, 1.0, 3.0)), 0.0)
    }

    @Test
    fun `an even count takes the mean of the two middle values`() {
        assertEquals(2.5, AccelerationMinuteFeatures.median(listOf(4.0, 1.0, 2.0, 3.0)), 0.0)
    }
}
