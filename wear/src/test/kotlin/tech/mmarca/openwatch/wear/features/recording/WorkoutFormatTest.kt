package tech.mmarca.openwatch.wear.features.recording

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.UnitSystem

class WorkoutFormatTest {

    private val locale = Locale.getDefault()

    @Before
    fun english() = Locale.setDefault(Locale.US)

    @After
    fun restore() = Locale.setDefault(locale)

    /** The speed that covers [metres] in [seconds]. */
    private fun speed(metres: Double, seconds: Double): Double = metres / seconds

    @Test
    fun `a pace is minutes and seconds a kilometre`() {
        assertEquals("5:42", formatPace(speed(1000.0, 342.0), UnitSystem.METRIC))
        assertEquals("12:05", formatPace(speed(1000.0, 725.0), UnitSystem.METRIC))
    }

    @Test
    fun `a pace is minutes and seconds a mile in imperial units`() {
        val eightMinuteMile = speed(1609.344, 480.0)
        assertEquals("8:00", formatPace(eightMinuteMile, UnitSystem.IMPERIAL))
        assertEquals("4:58", formatPace(eightMinuteMile, UnitSystem.METRIC))
    }

    @Test
    fun `a pace that rounds up to the minute reads as the next minute, never as sixty seconds`() {
        assertEquals("4:59", formatPace(speed(1000.0, 299.4), UnitSystem.METRIC))
        assertEquals("5:00", formatPace(speed(1000.0, 299.6), UnitSystem.METRIC))
    }

    @Test
    fun `a pace slower than 59 59 a unit is no pace`() {
        assertEquals("59:59", formatPace(speed(1000.0, 3599.0), UnitSystem.METRIC))
        assertNull(formatPace(speed(1000.0, 3600.0), UnitSystem.METRIC))
        // A drifting GPS: 0.11 m/s would be 151:31 a kilometre.
        assertNull(formatPace(0.11, UnitSystem.METRIC))
        // 0.4 m/s is 41:40 a kilometre and 67:03 a mile.
        assertEquals("41:40", formatPace(0.4, UnitSystem.METRIC))
        assertNull(formatPace(0.4, UnitSystem.IMPERIAL))
    }

    @Test
    fun `standing still is no pace`() {
        assertNull(formatPace(0.0, UnitSystem.METRIC))
        assertNull(formatPace(-1.0, UnitSystem.IMPERIAL))
    }

    @Test
    fun `a speed is kilometres or miles an hour to one decimal`() {
        assertEquals("9.0", formatSpeed(2.5, UnitSystem.METRIC))
        assertEquals("5.6", formatSpeed(2.5, UnitSystem.IMPERIAL))
        assertEquals("0.0", formatSpeed(0.0, UnitSystem.METRIC))
        assertEquals("27.4", formatSpeed(7.6, UnitSystem.METRIC))
    }

    @Test
    fun `a workout's distance is kilometres or miles to two decimals`() {
        assertEquals("3.24", formatWorkoutDistance(3_240, UnitSystem.METRIC))
        assertEquals("2.01", formatWorkoutDistance(3_240, UnitSystem.IMPERIAL))
        assertEquals("0.00", formatWorkoutDistance(0, UnitSystem.METRIC))
        assertEquals("0.99", formatWorkoutDistance(994, UnitSystem.METRIC))
        assertEquals("1.00", formatWorkoutDistance(996, UnitSystem.METRIC))
        assertEquals("26.22", formatWorkoutDistance(42_195, UnitSystem.IMPERIAL))
    }

    @Test
    fun `the decimal mark is the locale's`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("3,24", formatWorkoutDistance(3_240, UnitSystem.METRIC))
        assertEquals("9,0", formatSpeed(2.5, UnitSystem.METRIC))
    }

    @Test
    fun `pace and speed carry the unit system's unit`() {
        assertEquals(R.string.unit_pace_km, paceUnit(UnitSystem.METRIC))
        assertEquals(R.string.unit_pace_mi, paceUnit(UnitSystem.IMPERIAL))
        assertEquals(R.string.unit_kmh, speedUnit(UnitSystem.METRIC))
        assertEquals(R.string.unit_mph, speedUnit(UnitSystem.IMPERIAL))
    }
}
