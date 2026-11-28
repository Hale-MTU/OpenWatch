package tech.mmarca.openwatch.devices.core

/** A daily goal OpenWatch shares with the watch and with the apps connected over OpenLink. */
enum class DailyGoal { STEPS, ACTIVE_KCAL, WATER_ML }

/**
 * The daily goals as the apps connected over OpenLink share them, in plain numbers, so a device
 * family needs no OpenLink type: the link host keeps them and implements this. Read from memory.
 */
interface SharedGoals {

    /**
     * Each goal some app or the watch set: its value, or null where it was cleared (no goal). A
     * goal never set has no entry, so the watch keeps its own.
     */
    fun current(): Map<DailyGoal, Int?>

    /** A goal the watch took, shared with the connected apps; null clears it. */
    fun set(goal: DailyGoal, value: Int?)
}
