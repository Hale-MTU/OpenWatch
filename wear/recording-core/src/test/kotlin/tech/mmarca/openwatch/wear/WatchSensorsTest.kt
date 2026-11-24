package tech.mmarca.openwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchSensorsTest {

    @Test
    fun `each spot measurement needs its own sensor`() {
        assertEquals(SpotKind.entries.toSet(), WatchSensors.ALL.spotKinds)
        assertTrue(WatchSensors.NONE.spotKinds.isEmpty())
        assertEquals(setOf(SpotKind.HEART_RATE), WatchSensors.NONE.copy(heartRate = true).spotKinds)
        // A spot HRV needs single beats; the heart rate sensor alone is not enough.
        assertEquals(setOf(SpotKind.HEART_RATE, SpotKind.AIR_PRESSURE), WatchSensors.ALL.copy(heartBeat = false).spotKinds)
        assertEquals(setOf(SpotKind.AIR_PRESSURE), WatchSensors.NONE.copy(barometer = true, stepCounter = true, gps = true).spotKinds)
    }
}
