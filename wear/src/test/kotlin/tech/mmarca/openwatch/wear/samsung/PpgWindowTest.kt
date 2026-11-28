package tech.mmarca.openwatch.wear.samsung

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PpgWindowTest {

    private val start = 1_000_000L
    private val end = start + 300_000L

    private fun point(at: Long, status: Int = 0, green: Int = 100) = PpgPoint(at, green, status, red = 0, infrared = 0)

    @Test
    fun `only points the platform measured are kept, the others are counted`() {
        val window = PpgWindow(start)

        window.add(listOf(point(start + 40, status = 0), point(start + 80, status = -1), point(start + 120, status = 2)), 0L)

        assertEquals(1, window.times().size)
        assertEquals(2, window.invalid)
    }

    @Test
    fun `a window whose every point reads status -1 has no sample, and says how many it saw`() {
        // The layout of the answer read wrong: every point 0 with status -1.
        val window = PpgWindow(start)
        window.add((0 until 50).map { point(start + it * 40L, status = -1, green = 0) }, 0L)

        assertEquals(0, window.times().size)
        assertEquals(50, window.invalid)
    }

    @Test
    fun `points from before the window and past its end are dropped`() {
        val window = PpgWindow(start)

        window.add(listOf(point(start - 1), point(start), point(end), point(end + 1)), end)

        assertEquals(listOf(start.toDouble(), end.toDouble()), window.times().toList())
    }

    @Test
    fun `a point at or after the end is what closes the window, a flush is not`() {
        val window = PpgWindow(start)

        assertFalse(window.add(listOf(point(start + 40), point(end - 40)), end))
        assertFalse(window.coversEnd(end))
        assertTrue(window.add(listOf(point(end)), end))
        assertTrue(window.coversEnd(end))
    }

    @Test
    fun `more than the analysis needs is not kept`() {
        val window = PpgWindow(start)

        window.add((0 until PpgWindow.MAX_SAMPLES + 100).map { point(start + it) }, 0L)

        assertEquals(PpgWindow.MAX_SAMPLES, window.times().size)
    }
}
