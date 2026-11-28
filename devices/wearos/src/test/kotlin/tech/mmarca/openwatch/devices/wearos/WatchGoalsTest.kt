package tech.mmarca.openwatch.devices.wearos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openwatch.devices.core.DailyGoal

class WatchGoalsTest {

    @Test
    fun `a goal set goes to the watch, a cleared one switches the ring off, one never set is left alone`() {
        assertEquals(
            listOf("goal_steps" to "9000", "goal_kcal" to "0"),
            WatchGoals.tokens(mapOf(DailyGoal.STEPS to 9_000, DailyGoal.ACTIVE_KCAL to null)),
        )
        assertEquals("a water goal is always there", listOf("goal_water" to "2000"), WatchGoals.tokens(mapOf(DailyGoal.WATER_ML to null)))
        assertEquals("out of the watch's range", emptyList<Pair<String, String>>(), WatchGoals.tokens(mapOf(DailyGoal.STEPS to 50)))
    }

    @Test
    fun `a goal the watch holds is shared as a goal, other settings are not`() {
        assertEquals(DailyGoal.STEPS to 12_000, WatchGoals.goalOf("goal_steps", "12000"))
        assertEquals(DailyGoal.ACTIVE_KCAL to null, WatchGoals.goalOf("goal_kcal", "0"))
        assertEquals(DailyGoal.WATER_ML to 2_500, WatchGoals.goalOf("goal_water", "2500"))
        assertNull(WatchGoals.goalOf("goal_water", "0"))
        assertNull(WatchGoals.goalOf("goal_steps", "50"))
        assertNull(WatchGoals.goalOf("hr_day", "600"))
    }
}
