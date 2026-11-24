package tech.mmarca.openwatch.wear.features.quicklog

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.mmarca.openwatch.wear.UnitSystem
import tech.mmarca.openwatch.wearlink.WearLinkEntries
import tech.mmarca.openwatch.wearlink.WearLinkEntry

class LogValueSpecTest {

    private val previous = Locale.getDefault()

    @Before
    fun pinLocale() {
        // The display follows the locale; the decimal separator is not what these tests check.
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(previous)
    }

    private val temperature = LogValueSpecs.getValue(WearLinkEntry.Type.BODY_TEMPERATURE)
    private val glucose = LogValueSpecs.getValue(WearLinkEntry.Type.BLOOD_GLUCOSE)

    @Test
    fun `metric is stored and shown as it is`() {
        assertEquals(36.5, temperature.toDisplay(36.5, UnitSystem.METRIC), 1e-9)
        assertEquals(36.5, temperature.stored(36.5, UnitSystem.METRIC), 1e-9)
    }

    @Test
    fun `imperial is a straight line from metric and back, stored to the metric unit's decimals`() {
        assertEquals(97.7, temperature.toDisplay(36.5, UnitSystem.IMPERIAL), 1e-9)
        assertEquals(36.5, temperature.stored(97.7, UnitSystem.IMPERIAL), 1e-9)
        assertEquals(99.1, glucose.toDisplay(5.5, UnitSystem.IMPERIAL), 0.1)
        assertEquals(5.5, glucose.stored(99.0, UnitSystem.IMPERIAL), 1e-9)
    }

    @Test
    fun `a step is one unit in the last shown place, and mg per dL is whole`() {
        assertEquals(10, temperature.stepsPerUnit(UnitSystem.METRIC))
        assertEquals("36.5", temperature.format(36.5, UnitSystem.METRIC))
        assertEquals(1, glucose.stepsPerUnit(UnitSystem.IMPERIAL))
        assertEquals("99", glucose.format(99.1, UnitSystem.IMPERIAL))
    }

    @Test
    fun `every value's default is in its range, and its range is a line the link carries`() {
        for ((type, spec) in LogValueSpecs) {
            assertTrue("$type default inside its range", spec.metricDefault in spec.metricRange)
            for (value in listOf(spec.metricRange.start, spec.metricRange.endInclusive)) {
                val entry = WearLinkEntry.of(1L, type, value)
                assertEquals("$type at $value", entry, WearLinkEntries.parse(WearLinkEntries.format(entry)))
            }
        }
    }
}
