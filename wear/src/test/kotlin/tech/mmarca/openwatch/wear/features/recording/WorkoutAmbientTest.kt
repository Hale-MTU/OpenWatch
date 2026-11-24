package tech.mmarca.openwatch.wear.features.recording

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.UnitSystem
import tech.mmarca.openwatch.wear.workout.WorkoutSnapshot
import tech.mmarca.openwatch.wearlink.WorkoutSport

class WorkoutAmbientTest {

    private val snapshot = WorkoutSnapshot(
        activeSeconds = 3_725, paused = false, distanceMetres = 8_400, speedMetresPerSecond = 2.4,
        averageSpeedMetresPerSecond = 2.3, steps = 9_100, cadenceStepsPerMinute = 168, ascentMetres = 120,
        floors = null, heartRateBpm = 141, averageHeartRateBpm = 136, maxHeartRateBpm = 158, waitingForFix = false,
    )

    @Test
    fun `the ambient layout dashes what changes by the second and keeps the rest`() {
        assertEquals(
            listOf(
                AmbientLine(R.string.workout_heart_rate_placeholder),
                AmbientLine(R.string.workout_distance_placeholder),
                AmbientLine(R.string.workout_steps, 9_100),
                AmbientLine(R.string.workout_ascent, 120),
            ),
            ambientLines(WorkoutSport.RUNNING, snapshot, UnitSystem.METRIC),
        )
    }

    @Test
    fun `the ambient layout's distance and height follow the unit system`() {
        assertEquals(
            listOf(
                AmbientLine(R.string.workout_heart_rate_placeholder),
                AmbientLine(R.string.workout_distance_placeholder_mi),
                AmbientLine(R.string.workout_steps, 9_100),
                AmbientLine(R.string.workout_ascent_ft, 394), // 120 m
            ),
            ambientLines(WorkoutSport.RUNNING, snapshot, UnitSystem.IMPERIAL),
        )
    }

    @Test
    fun `a value the workout does not have gets no ambient line`() {
        val indoors = snapshot.copy(distanceMetres = null, heartRateBpm = null, averageHeartRateBpm = null, ascentMetres = 0)
        assertEquals(emptyList<AmbientLine>(), ambientLines(WorkoutSport.STATIONARY_BIKE, indoors, UnitSystem.METRIC))
    }

    @Test
    fun `the ambient clock has no seconds`() {
        assertEquals("1:02", ambientClock(3_725))
        assertEquals("0:00", ambientClock(59))
    }

    @Test
    fun `a workout opens with the controls left of the overview and only the pages it fills`() {
        assertEquals(
            listOf(WorkoutPage.CONTROLS, WorkoutPage.OVERVIEW, WorkoutPage.HEART, WorkoutPage.PACE),
            workoutPagesOf(workoutFieldsOf(WorkoutSport.RUNNING, gps = true)),
        )
        assertEquals(listOf(WorkoutPage.CONTROLS, WorkoutPage.OVERVIEW, WorkoutPage.HEART), workoutPagesOf(setOf(WorkoutField.HEART_RATE)))
        assertEquals(listOf(WorkoutPage.CONTROLS, WorkoutPage.OVERVIEW), workoutPagesOf(emptySet()))
    }
}
