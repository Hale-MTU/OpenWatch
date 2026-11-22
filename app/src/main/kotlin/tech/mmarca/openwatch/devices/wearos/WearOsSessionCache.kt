package tech.mmarca.openwatch.devices.wearos

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.mmarca.openwatch.wearlink.WearLinkOutcome
import tech.mmarca.openwatch.wearlink.WearLinkSession

/**
 * The open sessions of [BluetoothWearOsNodePort], at most one per watch, so the requests of a
 * sync share one connection and one hello instead of paying a connect, a service lookup and a
 * hello each. The watch's capabilities come with that hello, so a sync reads them once, and a
 * series the watch lacks costs no request at all.
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
 * answer stands. A session busy with one call is not shared: another call at the same moment opens
 * a connection of its own and closes it after, as every call did before.
 */
internal class WearOsSessionCache(
    /** Runs the idle closes and the closes when an owner ends; never a request. */
    private val idleScope: CoroutineScope,
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val maxAgeMillis: Long = MAX_AGE_MILLIS,
    private val maxIdleMillis: Long = MAX_IDLE_MILLIS,
) {

    private class Held(val key: String, val session: WearLinkSession, val openedAt: Long) {
        /** When the hello was answered or the latest request went out; the watch's idle timer restarts then. */
        var sentAt = openedAt
        var inUse = true

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

    private val lock = Any()
    private val held = HashMap<String, Held>()

    /**
     * [ask] over the session for [key], under one watchdog of [timeoutMillis] for the whole call;
     * null, and nothing sent, when the session's hello did not announce [capability]. [open]
     * connects and says hello when no session can be reused; a session it opens belongs to
     * [owner]. Throws what [open] throws.
     */
    suspend fun <T> request(
        key: String,
        owner: Job?,
        timeoutMillis: Long,
        capability: String?,
        open: (WearOsLinkGuard) -> WearLinkSession,
        ask: (WearLinkSession) -> WearLinkOutcome<T>,
    ): WearLinkOutcome<T>? = guarded(timeoutMillis) { guard ->
        take(key)?.let { reused ->
            val outcome = attempt(reused, guard, capability, ask)
            if (outcome !is WearLinkOutcome.Closed || guard.tripped) return@guarded outcome
            WearOsLog.log("session found closed; connecting again")
        }
        // Taken before the connect: the watch counts a connection's age from its accept.
        val openedAt = clock()
        attempt(adopt(key, open(guard), openedAt, owner), guard, capability, ask)
    }

    private fun <T> attempt(
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
            release(entry, answered)
        }
    }

    /** The idle, fresh session for [key], now in use; null when there is none. A stale one is closed. */
    private fun take(key: String): Held? {
        val stale = synchronized(lock) {
            val entry = held[key] ?: return null
            if (entry.inUse) return null
            val now = clock()
            if (now - entry.openedAt < maxAgeMillis && now - entry.sentAt < maxIdleMillis) {
                entry.inUse = true
                entry.uses++
                entry.idleClose?.cancel()
                return entry
            }
            held.remove(key)
            entry
        }
        stale.close()
        return null
    }

    /** Keeps a newly opened session for [owner], or uses it once when [key] already has one. */
    private fun adopt(key: String, session: WearLinkSession, openedAt: Long, owner: Job?): Held {
        val entry = Held(key, session, openedAt).apply { sentAt = clock() }
        synchronized(lock) {
            if (owner == null || key in held) return entry
            held[key] = entry
            // Dispatched: the owner may end on the main thread, and a close must not run there.
            entry.ownerEnd = owner.invokeOnCompletion { idleScope.launch { ownerEnded(entry) } }
        }
        return entry
    }

    private fun release(entry: Held, answered: Boolean) {
        synchronized(lock) {
            val kept = held[entry.key] === entry
            if (kept && answered && !entry.ownerEnded) {
                entry.inUse = false
                val use = entry.uses
                val closeAt = entry.sentAt + maxIdleMillis
                entry.idleClose = idleScope.launch {
                    delay((closeAt - clock()).coerceAtLeast(0))
                    idled(entry, use)
                }
                return
            }
            if (kept) held.remove(entry.key)
        }
        entry.close()
    }

    private fun idled(entry: Held, use: Int) {
        synchronized(lock) {
            if (held[entry.key] !== entry || entry.inUse || entry.uses != use) return
            held.remove(entry.key)
        }
        entry.close()
    }

    private fun ownerEnded(entry: Held) {
        synchronized(lock) {
            entry.ownerEnded = true
            // In use by another caller: closed when that request ends.
            if (held[entry.key] !== entry || entry.inUse) return
            held.remove(entry.key)
        }
        entry.close()
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
