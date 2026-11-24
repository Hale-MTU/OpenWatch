package tech.mmarca.openwatch.wear.features.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.wear.workout.WorkoutSnapshot
import tech.mmarca.openwatch.wearlink.WorkoutSport

class WorkoutRecordingStateTest {

    private val snapshot = WorkoutSnapshot(
        activeSeconds = 600, paused = true, distanceMetres = 1500, speedMetresPerSecond = null,
        averageSpeedMetresPerSecond = 2.5, steps = 1800, cadenceStepsPerMinute = 170, ascentMetres = 12,
        floors = null, heartRateBpm = 140, averageHeartRateBpm = 132, maxHeartRateBpm = 160, waitingForFix = false,
    )

    @Test
    fun `the recorder's snapshot fills the workout screen`() {
        val state = snapshot.toRecordingState(WorkoutSport.RUNNING, title = 42)
        assertEquals(ActivityType.RUN, state.activityType)
        assertEquals(42, state.title)
        assertEquals(600L, state.elapsedSeconds)
        assertEquals(1500, state.distanceMeters)
        assertEquals(1800, state.steps)
        assertTrue(state.paused)
    }

    @Test
    fun `without a speed now the live pace is empty, not the workout's average`() {
        // Paused, or stopped at a light for ten seconds: the tracker reports no speed.
        val state = snapshot.toRecordingState(WorkoutSport.BIKING, title = 42)
        assertNull(state.speedMetersPerSecond)
        assertEquals(2.5, state.averageSpeedMetersPerSecond!!, 0.0)
    }

    @Test
    fun `the summary's pace is the workout's average, not the speed at the stop`() {
        val state = snapshot.copy(speedMetresPerSecond = 1.4).toRecordingState(WorkoutSport.RUNNING, title = 42)
        assertEquals(1.4, state.speedMetersPerSecond!!, 0.0)
        assertEquals(2.5, state.averageSpeedMetersPerSecond!!, 0.0)
    }

    @Test
    fun `a route sport shows distance and pace only when the workout records a route`() {
        assertTrue(WorkoutField.PACE in workoutFieldsOf(WorkoutSport.RUNNING, gps = true))
        assertFalse(WorkoutField.DISTANCE in workoutFieldsOf(WorkoutSport.RUNNING, gps = false))
        assertTrue(WorkoutField.SPEED in workoutFieldsOf(WorkoutSport.BIKING, gps = true))
        assertEquals(setOf(WorkoutField.HEART_RATE), workoutFieldsOf(WorkoutSport.YOGA, gps = true))
    }

    @Test
    fun `pace or speed follows the sport's own description`() {
        for (sport in WorkoutSport.entries.filter { it.gps }) {
            val fields = workoutFieldsOf(sport, gps = true)
            assertEquals(sport.name, sport.pace, WorkoutField.PACE in fields)
            assertEquals(sport.name, !sport.pace, WorkoutField.SPEED in fields)
        }
    }

    @Test
    fun `a sport that counts steps shows them, with or without a route`() {
        assertTrue(WorkoutField.STEPS in workoutFieldsOf(WorkoutSport.TREADMILL, gps = false))
        assertTrue(WorkoutField.STEPS in workoutFieldsOf(WorkoutSport.WALKING, gps = false))
        assertFalse(WorkoutField.STEPS in workoutFieldsOf(WorkoutSport.BIKING, gps = true))
    }
}
