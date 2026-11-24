package tech.mmarca.openwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitSystemTest {

    @Test
    fun `water stays in millilitres in metric and is US fluid ounces in imperial`() {
        assertEquals(250.0, UnitSystem.METRIC.water(250.0), 0.0)
        assertEquals(8.45, UnitSystem.IMPERIAL.water(250.0), 0.005)
        assertEquals(67.6, UnitSystem.IMPERIAL.water(2_000.0), 0.05)
    }

    @Test
    fun `the names are stored, so neither is ever renamed`() {
        assertEquals(listOf("METRIC", "IMPERIAL"), UnitSystem.entries.map { it.name })
    }
}
