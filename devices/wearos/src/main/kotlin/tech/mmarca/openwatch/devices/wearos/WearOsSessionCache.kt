package tech.mmarca.openwatch.devices.wearos

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tech.mmarca.openwatch.wearlink.WearLinkOutcome
import tech.mmarca.openwatch.wearlink.WearLinkSession

/**
 * The phone's one owner of each watch's link, for [BluetoothWearOsNodePort].
 *
 * RFCOMM takes one connection per server channel between a phone and a watch, and the watch
 * serves everything on one channel: while one connection is open the phone's stack drops a
 * second, and the call that opened it gets no answer. So every connect, request and close for a
 * watch happens in a turn of its own, one at a time. A request that finds the link busy waits for
 * its turn, up to its own timeout, and then runs under a watchdog of that timeout; it never opens
 * a second connection. Turns go first come, first served, with one exception: a background request
 * (a window of the live heart rate) lets every other waiting request go first, and also waits
 * while another caller whose coroutine still runs asked less than [maxIdleMillis] ago, so a sync's
 * requests follow one another with no live window between them.
 *
 * The requests share the watch's one session, so a sync pays one connect, one service lookup and
 * one hello instead of one each. The watch's capabilities come with that hello, so a sync reads
 * them once, and a series the watch lacks costs no request at all.
 *
 * A session is reused while it is younger than [maxAgeMillis] and its last request went out less
 * than [maxIdleMillis] ago: the watch closes a connection 15 s after the last request arrived and
 * 600 s after it opened, and the margins keep every reused request inside both. It belongs to the
 * coroutine that opened it, a whole sync, and is closed when that coroutine ends, when it idles
 * past [maxIdleMillis], or when a request on it does not end in an answer. The link takes no radio
 * lease, so this is what holds the watch's radio no longer than the work that uses it. The watch
 * closes only when the phone does, so every close here is the phone's.
 *
 * A reused session that turns out closed is replaced once by a new connection, and that one's
 * answer stands.
 */
internal class WearOsSessionCache(
    /** Runs the idle closes, the closes when an owner ends and the turns a caller held back; never a request. */
    private val idleScope: CoroutineScope,
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val maxAgeMillis: Long = MAX_AGE_MILLIS,
    private val maxIdleMillis: Long = MAX_IDLE_MILLIS,
) {

    private class Held(val key: String, val session: WearLinkSession, val openedAt: Long) {
        /** When the hello was answered or the latest request went out; the watch's idle timer restarts then. */
        var sentAt = openedAt

        /** Counts the takes, so an idle close armed before the latest one does nothing. */
        var uses = 0
        var ownerEnded = false
        var idleClose: Job? = null
        var ownerEnd: DisposableHandle? = null

        fun close() {
            ownerEnd?.dispose()
            idleClose?.cancel()
            session.close()
        }
    }

    /** One request's place in a watch's queue. */
    private class Turn(val owner: Job?, val background: Boolean) {
        val signal = CompletableDeferred<Unit>()

        /** Set under the lock once the link is this turn's; [signal] follows outside it. */
        var granted = false
    }

    /** A caller that asked lately and whose coroutine still runs: a background turn waits for it. */
    private class Caller(var lastAt: Long, val ended: DisposableHandle)

    /** One watch: its session, whether a turn has it, who waits, and who asked lately. */
    private class Link {
        var session: Held? = null
        var busy = false
        val waiting = ArrayList<Turn>()
        val callers = HashMap<Job, Caller>()
        var recheck: Job? = null
    }

    private val lock = Any()
    private val links = HashMap<String, Link>()

    /**
     * [ask] over [watch]'s session for [key] (the session's token), in a turn of the watch's link:
     * waits for the turn up to [timeoutMillis], then runs under a watchdog of [timeoutMillis]. Null,
     * and nothing sent, when the session's hello did not announce [capability]. [open] connects and
     * says hello when no session can be reused; a session it opens belongs to [owner]. A
     * [background] request lets the others go first. Throws what [open] throws, and
     * [WearOsLinkFailure.NoAnswer] when the turn did not come in time.
     */
    suspend fun <T> request(
        watch: String,
        key: String,
        owner: Job?,
        timeoutMillis: Long,
        capability: String?,
        background: Boolean = false,
        open: (WearOsLinkGuard) -> WearLinkSession,
        ask: (WearLinkSession) -> WearLinkOutcome<T>,
    ): WearLinkOutcome<T>? {
        val link = synchronized(lock) { links.getOrPut(watch) { Link() } }
        val turn = Turn(owner, background)
        if (!awaitTurn(link, turn, timeoutMillis)) {
            WearOsLog.log("link busy for the whole timeout; nothing asked")
            throw WearOsLinkFailure.NoAnswer()
        }
        try {
            return guarded(timeoutMillis) { guard -> inTurn(link, key, owner, guard, capability, open, ask) }
        } finally {
            endTurn(link, turn)
        }
    }

    private fun <T> inTurn(
        link: Link,
        key: String,
        owner: Job?,
        guard: WearOsLinkGuard,
        capability: String?,
        open: (WearOsLinkGuard) -> WearLinkSession,
        ask: (WearLinkSession) -> WearLinkOutcome<T>,
    ): WearLinkOutcome<T>? {
        take(link, key)?.let { reused ->
            val outcome = attempt(link, reused, guard, capability, ask)
            if (outcome !is WearLinkOutcome.Closed || guard.tripped) return outcome
            WearOsLog.log("session found closed; connecting again")
        }
        // Taken before the connect: the watch counts a connection's age from its accept.
        val openedAt = clock()
        return attempt(link, adopt(link, key, open(guard), openedAt, owner), guard, capability, ask)
    }

    private fun <T> attempt(
        link: Link,
        entry: Held,
        guard: WearOsLinkGuard,
        capability: String?,
        ask: (WearLinkSession) -> WearLinkOutcome<T>,
    ): WearLinkOutcome<T>? {
        guard.watch { entry.session.close() }
        var answered = false
        try {
            if (capability != null && capability !in entry.session.peer.capabilities) {
                answered = true
                return null
            }
            synchronized(lock) { entry.sentAt = clock() }
            val outcome = ask(entry.session)
            // Only an answer leaves the stream where the next request can start.
            answered = outcome is WearLinkOutcome.Ok && !guard.tripped
            return outcome
        } finally {
            release(link, entry, answered)
        }
    }

    /** The watch's session when it can carry a request for [key]; null when there is none. One that cannot is closed. */
    private fun take(link: Link, key: String): Held? {
        val stale = synchronized(lock) {
            val entry = link.session ?: return null
            val now = clock()
            val fresh = now - entry.openedAt < maxAgeMillis && now - entry.sentAt < maxIdleMillis
            if (entry.key == key && fresh && !entry.ownerEnded) {
                entry.uses++
                entry.idleClose?.cancel()
                return entry
            }
            link.session = null
            entry
        }
        stale.close()
        return null
    }

    /** Keeps a newly opened session for [owner]; without an owner it serves this request only. */
    private fun adopt(link: Link, key: String, session: WearLinkSession, openedAt: Long, owner: Job?): Held {
        val entry = Held(key, session, openedAt).apply { sentAt = clock() }
        if (owner == null) return entry
        synchronized(lock) {
            link.session = entry
            // Dispatched: the owner may end on the main thread, and a close must not run there.
            entry.ownerEnd = owner.invokeOnCompletion { idleScope.launch { ownerEnded(link, entry) } }
        }
        return entry
    }

    private fun release(link: Link, entry: Held, answered: Boolean) {
        synchronized(lock) {
            val kept = link.session === entry
            if (kept && answered && !entry.ownerEnded) {
                val use = entry.uses
                val closeAt = entry.sentAt + maxIdleMillis
                entry.idleClose = idleScope.launch {
                    delay((closeAt - clock()).coerceAtLeast(0))
                    closeInTurn(link, entry) { entry.uses == use }
                }
                return
            }
            if (kept) link.session = null
        }
        entry.close()
    }

    private suspend fun ownerEnded(link: Link, entry: Held) {
        synchronized(lock) {
            entry.ownerEnded = true
            if (link.session !== entry) return
        }
        // A request on it now closes it when it ends; otherwise this turn does.
        closeInTurn(link, entry) { true }
    }

    /** Closes [entry] in a turn of its own, after the requests before it, if it is still the watch's session and [due]. */
    private suspend fun closeInTurn(link: Link, entry: Held, due: () -> Boolean) {
        val turn = Turn(owner = null, background = false)
        awaitTurn(link, turn, timeoutMillis = null)
        try {
            val closing = synchronized(lock) {
                (link.session === entry && due()).also { if (it) link.session = null }
            }
            if (closing) entry.close()
        } finally {
            endTurn(link, turn)
        }
    }

    /** Waits for [turn], for at most [timeoutMillis] when one is given; false when the time ran out first. */
    private suspend fun awaitTurn(link: Link, turn: Turn, timeoutMillis: Long?): Boolean {
        synchronized(lock) {
            link.waiting += turn
            grantLocked(link)
        }?.signal?.complete(Unit)
        val inTime = try {
            if (timeoutMillis == null) {
                turn.signal.await()
                true
            } else {
                withTimeoutOrNull(timeoutMillis) { turn.signal.await() } != null
            }
        } catch (cancelled: CancellationException) {
            if (!leave(link, turn)) endTurn(link, turn)
            throw cancelled
        }
        // A turn that came just as the time ran out is still taken.
        return inTime || !leave(link, turn)
    }

    /** Takes [turn] out of the queue; false when it had already come. */
    private fun leave(link: Link, turn: Turn): Boolean = synchronized(lock) {
        if (turn.granted) return false
        link.waiting.remove(turn)
        true
    }

    private fun endTurn(link: Link, turn: Turn) {
        synchronized(lock) {
            link.busy = false
            val owner = turn.owner
            if (!turn.background && owner != null && owner.isActive) noteCaller(link, owner)
            grantLocked(link)
        }?.signal?.complete(Unit)
    }

    /** The turn that now has the link, marked granted; null while the link is busy or no waiting turn may have it yet. */
    private fun grantLocked(link: Link): Turn? {
        if (link.busy) return null
        val next = link.waiting.firstOrNull { !it.background }
            ?: link.waiting.firstOrNull()?.takeIf { background ->
                val heldUntil = holdLocked(link, background)
                if (heldUntil != null) recheckAt(link, heldUntil)
                heldUntil == null
            }
            ?: return null
        link.waiting.remove(next)
        link.busy = true
        next.granted = true
        return next
    }

    /** [owner] just asked: a background turn waits until it has been quiet for [maxIdleMillis], or has ended. */
    private fun noteCaller(link: Link, owner: Job) {
        val now = clock()
        link.callers[owner]?.let { caller ->
            caller.lastAt = now
            return
        }
        link.callers[owner] = Caller(now, owner.invokeOnCompletion { callerEnded(link, owner) })
    }

    private fun callerEnded(link: Link, owner: Job) {
        synchronized(lock) {
            link.callers.remove(owner)
            grantLocked(link)
        }?.signal?.complete(Unit)
    }

    /** Until when another caller's work holds [turn] back; null when nothing does. Forgets the callers that are done. */
    private fun holdLocked(link: Link, turn: Turn): Long? {
        val now = clock()
        var until: Long? = null
        val callers = link.callers.entries.iterator()
        while (callers.hasNext()) {
            val (job, caller) = callers.next()
            val ends = caller.lastAt + maxIdleMillis
            if (!job.isActive || now >= ends) {
                caller.ended.dispose()
                callers.remove()
            } else if (job !== turn.owner) {
                until = maxOf(until ?: ends, ends)
            }
        }
        return until
    }

    /** Looks at the queue again at [at], once the callers holding a background turn back have gone quiet. */
    private fun recheckAt(link: Link, at: Long) {
        link.recheck?.cancel()
        link.recheck = idleScope.launch {
            delay((at - clock()).coerceAtLeast(0))
            synchronized(lock) { grantLocked(link) }?.signal?.complete(Unit)
        }
    }

    companion object {
        /** A minute inside the watch's 600 s limit on a connection, more than a request's 30 s watchdog. */
        const val MAX_AGE_MILLIS = 540_000L

        /** Five seconds inside the watch's 15 s idle timeout. */
        const val MAX_IDLE_MILLIS = 10_000L

        private const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * Closes the socket a call is using once the call runs out of time or is cancelled. `connect()`
 * and the reads block and ignore cancellation; closing the socket is what ends them.
 */
internal class WearOsLinkGuard {
    @Volatile
    private var close: (() -> Unit)? = null

    /** True once the call ran out of time or was cancelled; what the guard watched is closed. */
    @Volatile
    var tripped: Boolean = false
        private set

    /** Watches [close] from now on, in place of what it watched before; closes at once when already tripped. */
    fun watch(close: () -> Unit) {
        this.close = close
        if (tripped) close()
    }

    fun trip() {
        tripped = true
        close?.invoke()
    }
}

/**
 * Runs [block] under a [WearOsLinkGuard] that trips after [timeoutMillis] or when the caller is
 * cancelled, whichever comes first. The block runs inside the scope that owns the watchdog: a
 * session is never used outside it, since the scope only returns once the watchdog has run or
 * been cancelled.
 */
internal suspend fun <T> guarded(timeoutMillis: Long, block: (WearOsLinkGuard) -> T): T = coroutineScope {
    val guard = WearOsLinkGuard()
    val finished = AtomicBoolean(false)
    // Undispatched, so a cancel that comes before the watchdog ran still trips it.
    val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            delay(timeoutMillis)
        } finally {
            if (!finished.get()) guard.trip()
        }
    }
    try {
        block(guard)
    } finally {
        finished.set(true)
        watchdog.cancel()
    }
}
