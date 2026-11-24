package tech.mmarca.openwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Test

/** The quick log tile's add-water button: one glass per tap, however often the tile is asked for again. */
class WaterTapsTest {

    private val prefs = FakeSharedPreferences()
    private var glasses = 0

    @Test
    fun `a tap logs one glass and moves the button's id on`() {
        val taps = WaterTaps(prefs)
        val tapped = taps.id

        taps.onTap(tapped) { glasses++ }

        assertEquals(1, glasses)
        assertEquals(WaterTaps.idOf(1), taps.id)
    }

    @Test
    fun `a request that repeats a tap already logged logs nothing`() {
        val taps = WaterTaps(prefs)
        val tapped = taps.id
        taps.onTap(tapped) { glasses++ }

        // A refresh, or the request the logged glass itself asked for, still carries the last tap's id.
        taps.onTap(tapped) { glasses++ }
        taps.onTap(tapped) { glasses++ }

        assertEquals(1, glasses)
    }

    @Test
    fun `no tap, or another button's, logs nothing`() {
        val taps = WaterTaps(prefs)

        taps.onTap("") { glasses++ }
        taps.onTap("open:quick_log") { glasses++ }

        assertEquals(0, glasses)
        assertEquals(WaterTaps.idOf(0), taps.id)
    }

    @Test
    fun `each new tap logs its own glass, across a restart`() {
        WaterTaps(prefs).let { taps -> taps.onTap(taps.id) { glasses++ } }
        WaterTaps(prefs).let { taps -> taps.onTap(taps.id) { glasses++ } }

        assertEquals(2, glasses)
        assertEquals(2, prefs.getInt(WaterTaps.KEY_CLICKS, 0))
    }
}
