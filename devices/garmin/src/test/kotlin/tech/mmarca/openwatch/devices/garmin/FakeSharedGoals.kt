package tech.mmarca.openwatch.devices.garmin

import tech.mmarca.openwatch.devices.core.DailyGoal
import tech.mmarca.openwatch.devices.core.SharedGoals

/** The shared goals in memory: what [set] took, or what a test put in [goals]. */
internal class FakeSharedGoals(val goals: MutableMap<DailyGoal, Int?> = mutableMapOf()) : SharedGoals {

    override fun current(): Map<DailyGoal, Int?> = goals.toMap()

    override fun set(goal: DailyGoal, value: Int?) {
        goals[goal] = value
    }
}
