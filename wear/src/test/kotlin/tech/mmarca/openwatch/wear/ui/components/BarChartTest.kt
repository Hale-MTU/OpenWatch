package tech.mmarca.openwatch.wear.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BarChartTest {

    private fun assertShares(expected: List<Float?>, actual: List<Float?>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEachIndexed { index, (wanted, got) ->
            if (wanted == null) assertNull("bar $index", got) else assertEquals("bar $index", wanted, got!!, 1e-6f)
        }
    }

    @Test
    fun `a total's bars are shares of the largest, from zero`() {
        assertShares(listOf(0.5f, 1f, 0f, 0.25f), barShares(listOf(4_000.0, 8_000.0, 0.0, 2_000.0), fromZero = true))
    }

    @Test
    fun `a level's bars start a quarter of their range under the lowest, so they do not all look alike`() {
        // Blood oxygen from 95 to 98: from zero the bars would be 97 to 100 percent of the height.
        val shares = barShares(listOf(95.0, 98.0, 96.5), fromZero = false)

        // The floor is 95 - 0.75 = 94.25, the range above it 3.75.
        assertShares(listOf(0.2f, 1f, 0.6f), shares)
    }

    @Test
    fun `a day without data gets no bar, in a total and in a level`() {
        assertShares(listOf(1f, null, 0.5f), barShares(listOf(10.0, null, 5.0), fromZero = true))
        assertShares(listOf(0.2f, null, 1f), barShares(listOf(60.0, null, 70.0), fromZero = false))
        assertShares(listOf(null, null), barShares(listOf(null, null), fromZero = false))
    }

    @Test
    fun `equal levels, or one alone, draw bars of one height`() {
        assertShares(listOf(0.6f, 0.6f), barShares(listOf(36.4, 36.4), fromZero = false))
        assertShares(listOf(0.6f), barShares(listOf(58.0), fromZero = false))
    }

    @Test
    fun `a level's floor never goes under zero, and totals of nothing stay at the stub`() {
        // 1 and 9: a quarter of the range under 1 would be -1.
        assertShares(listOf(1 / 9f, 1f), barShares(listOf(1.0, 9.0), fromZero = false))
        assertShares(listOf(0f, 0f), barShares(listOf(0.0, 0.0), fromZero = true))
    }
}
