package tech.mmarca.openwatch.wear.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestDetectorTest {

    private val t0 = 1_700_000_000_000L

    /** Feeds 5 readings a second for [seconds] from [from]: a flat 9.81 when [still], a swing when not. */
    private fun feed(d: RestDetector, from: Long, seconds: Int, still: Boolean) {
        for (i in 0 until seconds * 5) {
            val at = from + i * 200L
            d.onAcceleration(at, if (still) 9.81 else 9.81 + if (i % 2 == 0) 3.0 else -3.0)
        }
    }

    @Test fun `a minute of stillness after movement is a rest that began where the movement ended`() {
        val d = RestDetector()
        feed(d, t0, 30, still = false)
        feed(d, t0 + 30_000, 70, still = true)

        assertEquals(t0 + 30_000, d.restStartMillis(t0 + 100_000))
    }

    @Test fun `less than a minute is no rest`() {
        val d = RestDetector()
        feed(d, t0, 30, still = false)
        feed(d, t0 + 30_000, 50, still = true)

        assertNull(d.restStartMillis(t0 + 80_000))
    }

    @Test fun `moving again breaks the rest`() {
        val d = RestDetector()
        feed(d, t0, 70, still = true)
        feed(d, t0 + 70_000, 10, still = false)

        assertNull(d.restStartMillis(t0 + 80_000))
    }

    @Test fun `a gap in the readings is not a rest`() {
        val d = RestDetector()
        feed(d, t0, 30, still = true)
        feed(d, t0 + 40_000, 40, still = true)

        // The block after the gap starts a new run; only 40 s of it so far.
        assertNull(d.restStartMillis(t0 + 80_000))
    }

    @Test fun `a sensor that went quiet reports no rest`() {
        val d = RestDetector()
        feed(d, t0, 80, still = true)

        assertNull(d.restStartMillis(t0 + 80_000 + 60_000))
    }
}
