package tech.mmarca.openwatch.wear

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepHoldBudgetTest {

    private val zone = ZoneId.of("Europe/Madrid")
    private val budget = SleepHoldBudget { zone }

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `minutes are counted by how the hold ran`() {
        budget.update(at(10, 1), SleepHoldBudget.Mode.LOCK)
        budget.update(at(10, 3), SleepHoldBudget.Mode.SYNCED)
        budget.update(at(10, 4, 30), SleepHoldBudget.Mode.NONE)

        val today = budget.today(at(10, 12))
        assertEquals(120L, today.lockMinutes)
        assertEquals(90L, today.syncedMinutes)
    }

    @Test
    fun `a night across midnight is split, and the finished day is handed back once`() {
        budget.update(at(10, 23), SleepHoldBudget.Mode.LOCK)

        val ended = budget.update(at(11, 6), SleepHoldBudget.Mode.NONE)

        assertEquals(listOf(SleepHoldBudget.Day(LocalDate.of(2026, 11, 10), 60, 0)), ended)
        assertEquals(360L, budget.today(at(11, 7)).lockMinutes)
        assertTrue(budget.update(at(11, 8), SleepHoldBudget.Mode.NONE).isEmpty())
    }

    @Test
    fun `today includes the hold that is still running`() {
        budget.update(at(10, 1), SleepHoldBudget.Mode.SYNCED)

        assertEquals(30L, budget.today(at(10, 1, 30)).syncedMinutes)
    }

    @Test
    fun `passing the lock budget is told once a day`() {
        budget.update(at(10, 0), SleepHoldBudget.Mode.LOCK)

        assertNull(budget.newlyOverBudget(at(10, 1)))
        val over = budget.newlyOverBudget(at(10, 2, 1))
        assertNotNull(over)
        assertEquals(121L, over!!.lockMinutes)
        assertNull(budget.newlyOverBudget(at(10, 5)))
    }

    @Test
    fun `a clock set back credits nothing`() {
        budget.update(at(10, 3), SleepHoldBudget.Mode.LOCK)
        budget.update(at(10, 2), SleepHoldBudget.Mode.LOCK)

        assertEquals(0L, budget.today(at(10, 2)).lockMinutes)
    }
}
