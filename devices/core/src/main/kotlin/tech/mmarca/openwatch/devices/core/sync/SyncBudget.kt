package tech.mmarca.openwatch.devices.core.sync

import android.os.SystemClock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/**
 * How long a sync may run, carried in its coroutine context. The scheduled worker sets one
 * below WorkManager's ten-minute job limit; an integration checks it where stopping is safe
 * (between series, past a saved cursor) and reports that more remains, so the sync ends
 * cleanly and its continuation picks up, rather than being cancelled mid-write at the limit.
 * A sync without one, a tapped one, has no budget.
 */
class SyncBudget(
    private val deadlineMillis: Long,
    private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
) : AbstractCoroutineContextElement(Key) {

    val isSpent: Boolean get() = elapsedMillis() >= deadlineMillis

    companion object Key : CoroutineContext.Key<SyncBudget> {
        /** A budget of [millis] from now. */
        fun forNext(millis: Long, elapsedMillis: () -> Long = SystemClock::elapsedRealtime) =
            SyncBudget(elapsedMillis() + millis, elapsedMillis)
    }
}

/** Whether the running sync's [SyncBudget] is spent. False without one. */
suspend fun syncBudgetSpent(): Boolean = currentCoroutineContext()[SyncBudget]?.isSpent == true
