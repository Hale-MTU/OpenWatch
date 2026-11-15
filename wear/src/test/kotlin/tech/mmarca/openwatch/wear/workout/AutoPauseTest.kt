package tech.mmarca.openwatch.wear.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.wearlink.WorkoutSport

class AutoPauseTest {

    private val t0 = 1_700_000_000_000L

    private fun fix(seconds: Int, speed: Double?, metres: Double = 0.0, accuracy: Double = 5.0) = WorkoutFix(
        epochMillis = t0 + seconds * 1000L,
        latitude = 48.0 + metres / 111_195.0,
        longitude = 11.0,
        accuracyMetres = accuracy,
        speedMetresPerSecond = speed,
    )

    @Test fun `a runner who stops is paused from the last moment of movement`() {
        val auto = AutoPause(WorkoutSport.RUNNING)
        for (s in 0..20) auto.onFix(fix(s, 3.0))
        for (s in 21..40) auto.onFix(fix(s, 0.1))

        assertNull(auto.verdict(t0 + 25_000, pausedByThis = false))
        assertEquals(AutoPause.Verdict.PAUSE, auto.verdict(t0 + 40_000, pausedByThis = false))
        assertEquals(t0 + 20_000, auto.pauseAt)
    }

    @Test fun `a short stop at a crossing is no pause`() {
        val auto = AutoPause(WorkoutSport.RUNNING)
        for (s in 0..20) auto.onFix(fix(s, 3.0))
        for (s in 21..24) auto.onFix(fix(s, 0.1))
        for (s in 25..30) auto.onFix(fix(s, 3.0))

        assertNull(auto.verdict(t0 + 30_000, pausedByThis = false))
    }

    @Test fun `going on needs a few fast fixes in a row`() {
        val auto = AutoPause(WorkoutSport.RUNNING)
        for (s in 0..20) auto.onFix(fix(s, 0.1))
        auto.onFix(fix(21, 2.0))
        assertNull(auto.verdict(t0 + 21_000, pausedByThis = true))
        auto.onFix(fix(22, 0.2))
        auto.onFix(fix(23, 2.0))
        auto.onFix(fix(24, 2.0))
        assertNull(auto.verdict(t0 + 24_000, pausedByThis = true))
        auto.onFix(fix(25, 2.0))
        assertEquals(AutoPause.Verdict.RESUME, auto.verdict(t0 + 25_000, pausedByThis = true))
    }

    @Test fun `a lost signal is no stop`() {
        val auto = AutoPause(WorkoutSport.BIKING)
        for (s in 0..10) auto.onFix(fix(s, 6.0))

        assertNull(auto.verdict(t0 + 60_000, pausedByThis = false))
    }

    @Test fun `a cyclist crawling at a walking pace counts as stopped`() {
        val auto = AutoPause(WorkoutSport.BIKING)
        for (s in 0..10) auto.onFix(fix(s, 6.0))
        for (s in 11..25) auto.onFix(fix(s, 0.8))

        assertEquals(AutoPause.Verdict.PAUSE, auto.verdict(t0 + 25_000, pausedByThis = false))
    }

    @Test fun `a fix without a speed uses the one worked out from the fix before`() {
        val auto = AutoPause(WorkoutSport.RUNNING)
        for (s in 0..20) auto.onFix(fix(s, null, metres = s * 3.0))
        for (s in 21..40) auto.onFix(fix(s, null, metres = 60.0))

        assertEquals(AutoPause.Verdict.PAUSE, auto.verdict(t0 + 40_000, pausedByThis = false))
    }

    @Test fun `a fix with a poor accuracy is ignored`() {
        val auto = AutoPause(WorkoutSport.RUNNING)
        for (s in 0..20) auto.onFix(fix(s, 3.0))
        for (s in 21..40) auto.onFix(fix(s, 0.0, accuracy = 80.0))

        assertNull(auto.verdict(t0 + 40_000, pausedByThis = false))
    }

    @Test fun `only running and cycling pause by themselves`() {
        assertTrue(AutoPause.appliesTo(WorkoutSport.RUNNING))
        assertTrue(AutoPause.appliesTo(WorkoutSport.BIKING))
        assertFalse(AutoPause.appliesTo(WorkoutSport.WALKING))
        assertFalse(AutoPause.appliesTo(WorkoutSport.TREADMILL))
    }
}

class WorkoutTrackerAutoPauseTest {

    private val t0 = 1_700_000_040_000L

    private fun fix(seconds: Int, speed: Double, metres: Double) = WorkoutFix(
        epochMillis = t0 + seconds * 1000L, latitude = 48.0 + metres / 111_195.0, longitude = 11.0,
        accuracyMetres = 5.0, speedMetresPerSecond = speed,
    )

    @Test fun `the tracker pauses on a stop, counts no time for the wait, and goes on by itself`() {
        val tracker = WorkoutTracker(WorkoutSport.RUNNING, t0)
        for (s in 0..30) tracker.onFix(fix(s, 3.0, s * 3.0))
        for (s in 31..50) tracker.onFix(fix(s, 0.0, 90.0))
        assertTrue(tracker.autoPauseStep(t0 + 50_000))
        assertTrue(tracker.snapshot(t0 + 50_000).autoPaused)
        assertEquals(30, tracker.snapshot(t0 + 50_000).activeSeconds)

        for (s in 51..54) tracker.onFix(fix(s, 3.0, 90.0 + (s - 50) * 3.0))
        assertTrue(tracker.autoPauseStep(t0 + 54_000))
        assertFalse(tracker.snapshot(t0 + 54_000).paused)
        assertFalse(tracker.snapshot(t0 + 54_000).autoPaused)
    }

    @Test fun `a pause the wearer made is not undone`() {
        val tracker = WorkoutTracker(WorkoutSport.RUNNING, t0)
        for (s in 0..10) tracker.onFix(fix(s, 3.0, s * 3.0))
        tracker.pause(t0 + 10_000)
        for (s in 11..20) tracker.onFix(fix(s, 3.0, s * 3.0))

        assertFalse(tracker.autoPauseStep(t0 + 20_000))
        assertTrue(tracker.isPaused)
    }

    @Test fun `walking and a switched off setting never pause`() {
        for (tracker in listOf(WorkoutTracker(WorkoutSport.WALKING, t0), WorkoutTracker(WorkoutSport.RUNNING, t0, useAutoPause = false))) {
            for (s in 0..30) tracker.onFix(fix(s, 0.0, 0.0))
            assertFalse(tracker.autoPauseStep(t0 + 30_000))
        }
    }
}
