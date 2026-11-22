package tech.mmarca.openwatch.wear

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The minutes a sleep hold kept the processor, per local day, by how it held
 * it: a lasting wake lock or synced wake-ups. The sleep recorder tells it the
 * hold at each decision ([update]); the debug protocol carries today's
 * figures in every snapshot and one line per finished day against
 * [LOCK_BUDGET_MINUTES], so a worn week shows what the hold costs and where
 * it went over. It only counts: nothing is held back when the budget is
 * spent. Pure, and safe to read from another thread.
 */
internal class SleepHoldBudget(private val zone: () -> ZoneId = ZoneId::systemDefault) {

    enum class Mode { NONE, LOCK, SYNCED }

    data class Day(val date: LocalDate, val lockMinutes: Long, val syncedMinutes: Long) {
        val overBudget: Boolean get() = lockMinutes > LOCK_BUDGET_MINUTES
    }

    private var mode = Mode.NONE
    private var since: Long? = null
    private var day: LocalDate? = null
    private var lockMillis = 0L
    private var syncedMillis = 0L
    private var toldOverBudget: LocalDate? = null

    /**
     * The hold is [next] from [nowMillis] on: the time since the last call
     * goes to the mode that ran, split at local midnight. Returns the days
     * that ended since, oldest first. A clock set back credits nothing.
     */
    @Synchronized
    fun update(nowMillis: Long, next: Mode): List<Day> {
        val ended = ArrayList<Day>()
        val from = since
        if (from != null && nowMillis > from) {
            var cursor: Long = from
            var current = day ?: dateOf(from)
            val today = dateOf(nowMillis)
            while (current < today) {
                val midnight = startOf(current.plusDays(1))
                credit(midnight - cursor)
                ended += Day(current, minutes(lockMillis), minutes(syncedMillis))
                lockMillis = 0
                syncedMillis = 0
                cursor = maxOf(cursor, midnight)
                current = current.plusDays(1)
            }
            credit(nowMillis - cursor)
            day = current
        } else if (day == null) {
            day = dateOf(nowMillis)
        }
        mode = next
        since = nowMillis
        return ended
    }

    /** Today's figures as of [nowMillis], the hold that runs now included. */
    @Synchronized
    fun today(nowMillis: Long): Day {
        val running = since?.let { (nowMillis - it).coerceAtLeast(0) } ?: 0L
        return Day(
            date = day ?: dateOf(nowMillis),
            lockMinutes = minutes(lockMillis + if (mode == Mode.LOCK) running else 0),
            syncedMinutes = minutes(syncedMillis + if (mode == Mode.SYNCED) running else 0),
        )
    }

    /** Today's figures the first time today's lock passes the budget; null otherwise. */
    @Synchronized
    fun newlyOverBudget(nowMillis: Long): Day? {
        val current = today(nowMillis)
        if (!current.overBudget || toldOverBudget == current.date) return null
        toldOverBudget = current.date
        return current
    }

    private fun credit(millis: Long) {
        if (millis <= 0) return
        when (mode) {
            Mode.LOCK -> lockMillis += millis
            Mode.SYNCED -> syncedMillis += millis
            Mode.NONE -> Unit
        }
    }

    private fun dateOf(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone()).toLocalDate()

    private fun startOf(date: LocalDate): Long = date.atStartOfDay(zone()).toInstant().toEpochMilli()

    private fun minutes(millis: Long): Long = millis / MINUTE_MILLIS

    companion object {
        /**
         * The lasting lock a day may cost before the debug protocol marks it:
         * Play's excessive partial wake lock threshold, two hours of held
         * non-exempt partial wake locks in a day (verify the current figure
         * and the Wear exemptions). A night on the lock is six to nine.
         */
        const val LOCK_BUDGET_MINUTES = 120L

        private const val MINUTE_MILLIS = 60_000L
    }
}
