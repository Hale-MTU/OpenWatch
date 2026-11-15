package tech.mmarca.openwatch.wear.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openwatch.wearlink.WearLinkProtocol
import tech.mmarca.openwatch.wearlink.WorkoutSport

class ActivityDetectorTest {

    /** A minute boundary. */
    private val t0 = 1_700_000_040_000L

    private fun rows(vararg stepsPerMinute: Int) = stepsPerMinute.mapIndexed { i, steps ->
        WearLinkProtocol.StepMinute(t0 + i * 60_000L, steps)
    }

    /** Half way into the minute after the last row, so every row is a complete minute. */
    private fun nowAfter(count: Int) = t0 + count * 60_000L + 30_000L

    @Test fun `twelve minutes at a brisk pace are a walk from the first of them`() {
        val found = ActivityDetector.detect(rows(*IntArray(12) { 105 }), nowAfter(12))!!

        assertEquals(WorkoutSport.WALKING, found.sport)
        assertEquals(t0, found.startMillis)
        assertEquals(12, found.minutes)
        assertEquals(12 * 105, found.steps)
    }

    @Test fun `a steady high cadence is a run`() {
        val found = ActivityDetector.detect(rows(*IntArray(11) { 165 }), nowAfter(11))!!

        assertEquals(WorkoutSport.RUNNING, found.sport)
    }

    @Test fun `nine minutes are too short`() {
        assertNull(ActivityDetector.detect(rows(*IntArray(9) { 110 }), nowAfter(9)))
    }

    @Test fun `a strolling pace is no workout`() {
        assertNull(ActivityDetector.detect(rows(*IntArray(20) { 60 }), nowAfter(20)))
    }

    @Test fun `one slow minute at a crossing does not break the walk`() {
        val steps = IntArray(12) { 105 }.also { it[6] = 45 }

        val found = ActivityDetector.detect(rows(*steps), nowAfter(12))!!

        assertEquals(12, found.minutes)
    }

    @Test fun `a pause of several minutes ends the run and only the part since counts`() {
        val steps = IntArray(8) { 105 } + IntArray(3) { 5 } + IntArray(6) { 110 }

        assertNull(ActivityDetector.detect(rows(*steps), nowAfter(17)))
    }

    @Test fun `an activity that ended minutes ago is not offered`() {
        assertNull(ActivityDetector.detect(rows(*IntArray(12) { 105 }), nowAfter(12) + 5 * 60_000L))
    }

    @Test fun `the minute still being written is left out`() {
        // Eleven complete minutes and a twelfth, half written, with few steps so far.
        val found = ActivityDetector.detect(rows(*(IntArray(11) { 105 } + intArrayOf(12))), nowAfter(11) - 30_000L + 30_000L)

        assertNotNull(found)
        assertEquals(11, found!!.minutes)
    }
}
