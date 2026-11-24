package tech.mmarca.openwatch.devices.wearos

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearOsClockTest {

    @Test
    fun `the skew is how far the watch is ahead, and a watch that sent no clock has none`() {
        assertEquals(Duration.ofMinutes(3), WearOsClock.skew(watchMillis = 181_000, phoneMillis = 1_000))
        assertEquals(Duration.ofSeconds(-30), WearOsClock.skew(watchMillis = 0, phoneMillis = 30_000))
        assertNull(WearOsClock.skew(watchMillis = null, phoneMillis = 1_000))
    }
}
