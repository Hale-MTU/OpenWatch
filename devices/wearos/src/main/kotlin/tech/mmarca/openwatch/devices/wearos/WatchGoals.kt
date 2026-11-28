package tech.mmarca.openwatch.devices.wearos

import tech.mmarca.openwatch.devices.core.DailyGoal
import tech.mmarca.openwatch.wearlink.WearLinkPreferences as Preferences

/**
 * The daily goals as the watch holds them (its `goal_*` settings) and as the connected apps share
 * them ([tech.mmarca.openwatch.devices.core.SharedGoals]), one to the other. Pure. A goal cleared
 * in an app means no goal: the watch draws no step or energy ring then. The water goal is always
 * there, so a cleared one is the default.
 */
object WatchGoals {

    private class Goal(val goal: DailyGoal, val token: String, val range: IntRange, val clearable: Boolean)

    private val GOALS = listOf(
        Goal(DailyGoal.STEPS, Preferences.GOAL_STEPS, Preferences.STEP_GOAL_RANGE, clearable = true),
        Goal(DailyGoal.ACTIVE_KCAL, Preferences.GOAL_ACTIVE_KCAL, Preferences.ACTIVE_KCAL_GOAL_RANGE, clearable = true),
        Goal(DailyGoal.WATER_ML, Preferences.GOAL_WATER_ML, Preferences.WATER_GOAL_RANGE, clearable = false),
    )

    /** Two litres, the watch's own until a goal is set. */
    const val DEFAULT_WATER_GOAL_ML = 2_000

    /**
     * The watch settings [shared] holds a goal for, as `SET_PREF` takes them; a goal never set is
     * not in it, so the watch's own stays, and one out of the watch's range is left out too.
     */
    fun tokens(shared: Map<DailyGoal, Int?>): List<Pair<String, String>> = GOALS.mapNotNull { goal ->
        if (goal.goal !in shared) return@mapNotNull null
        val value = shared[goal.goal]
        when {
            value == null -> goal.token to if (goal.clearable) Preferences.OFF else DEFAULT_WATER_GOAL_ML.toString()
            value in goal.range -> goal.token to value.toString()
            else -> null
        }
    }

    /**
     * The goal a watch setting stands for, with its value (null to clear); null for a setting that
     * is no goal, or a value the watch would not take.
     */
    fun goalOf(token: String, value: String): Pair<DailyGoal, Int?>? {
        val goal = GOALS.firstOrNull { it.token == token } ?: return null
        val number = value.toIntOrNull() ?: return null
        if (number == 0) return if (goal.clearable) goal.goal to null else null
        return (goal.goal to number).takeIf { number in goal.range }
    }
}
