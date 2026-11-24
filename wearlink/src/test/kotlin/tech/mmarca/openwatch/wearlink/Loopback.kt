package tech.mmarca.openwatch.wearlink

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Client and server in one JVM over piped streams: the server runs on its
 * own thread, the test drives the client. Buffers are large enough for a
 * full page; closing either end's connection closes both pipes.
 */
class Loopback(
    val trust: MemoryTrustStore = MemoryTrustStore(),
    val handler: MemoryHandler = MemoryHandler(),
    options: WearLinkServer.Options = WearLinkServer.Options(),
    val phoneAddress: String = "AA:BB:CC:DD:EE:01",
) {
    val server = WearLinkServer(trust, handler, options)

    /** One connection: a server thread serving it, and the client's end, its lines kept in [transcript] when given. */
    inner class Link(transcript: Transcript? = null) {
        private val toServer = PipedOutputStream()
        private val fromServer = PipedOutputStream()
        private val serverInput = PipedInputStream(toServer, BUFFER)
        private val clientInput = PipedInputStream(fromServer, BUFFER)
        private val outcome = AtomicReference<WearLinkServer.Outcome?>()
        private val served = CountDownLatch(1)

        /**
         * Closing either end closes all four pipe ends: a closed Bluetooth
         * socket unblocks both sides' reads, and a piped reader only wakes
         * when its writer closes.
         */
        private fun closeAll() {
            runCatching { toServer.close() }
            runCatching { fromServer.close() }
            runCatching { clientInput.close() }
            runCatching { serverInput.close() }
        }

        val clientConnection = WearLinkConnection(
            transcript?.received(clientInput) ?: clientInput,
            transcript?.sent(toServer) ?: toServer,
            "watch",
        ) { closeAll() }
        private val serverConnection = WearLinkConnection(serverInput, fromServer, phoneAddress) { closeAll() }

        val serverThread = Thread({
            outcome.set(server.serve(serverConnection))
            served.countDown()
        }, "Loopback-server").apply { start() }

        /** The server's outcome once it returned, within [seconds]. */
        fun serverOutcome(seconds: Long = 5): WearLinkServer.Outcome? {
            served.await(seconds, TimeUnit.SECONDS)
            return outcome.get()
        }

        fun serverReturned(): Boolean = served.count == 0L
    }

    fun connect(transcript: Transcript? = null): Link = Link(transcript)

    private companion object {
        const val BUFFER = 256 * 1024
    }
}

/**
 * A golden transcript: every line the phone sent (`> `) and received (`< `), in the order the
 * phone's thread saw them, from the bytes on the wire.
 */
class Transcript {
    private val kept = Collections.synchronizedList(ArrayList<String>())

    val lines: List<String> get() = synchronized(kept) { kept.toList() }

    fun add(line: String) {
        kept += line
    }

    fun sent(output: OutputStream): OutputStream = object : FilterOutputStream(output) {
        private val line = Line("> ")
        override fun write(b: Int) {
            line.feed(b)
            out.write(b)
        }
    }

    fun received(input: InputStream): InputStream = object : FilterInputStream(input) {
        private val line = Line("< ")
        override fun read(): Int = super.read().also { if (it >= 0) line.feed(it) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { n -> for (i in off until off + maxOf(n, 0)) line.feed(b[i].toInt() and 0xff) }
    }

    private inner class Line(private val prefix: String) {
        private val bytes = ByteArrayOutputStream()
        fun feed(b: Int) {
            if (b == '\n'.code) {
                add(prefix + bytes.toString(Charsets.UTF_8))
                bytes.reset()
            } else {
                bytes.write(b)
            }
        }
    }
}

class MemoryTrustStore : WearLinkTrustStore {
    val tokens = HashMap<String, String>()
    val blocked = HashSet<String>()
    val pending = ArrayList<Triple<String, String, String>>()

    override fun tokenFor(peerAddress: String): String? = tokens[peerAddress]
    override fun isBlocked(peerAddress: String): Boolean = peerAddress in blocked
    override fun notePending(peerAddress: String, token: String, peerName: String) {
        pending += Triple(peerAddress, token, peerName)
    }
}

/**
 * A watch in memory. Its series rows are kept as the watch's store keeps them: one per key
 * and time, numbered on every insert and every replace, and `SINCE` answered by those numbers.
 */
class MemoryHandler(
    val phoneActive: MutableList<Long> = mutableListOf(),
    var workouts: List<WearLinkWorkout> = emptyList(),
    val preferences: MutableMap<String, String> = linkedMapOf(WearLinkProtocol.Preferences.HEART_RATE_DAY to "10"),
    /** What one `HR_LIVE` window hands out; empty is a watch off the wrist. */
    var live: List<WearLinkProtocol.HeartRateSample> = emptyList(),
    var name: String = "Galaxy Watch8 (89FZ)",
    var announced: Set<String> = setOf(
        WearLinkProtocol.CAP_HEART_RATE, WearLinkProtocol.CAP_SLEEP_MINUTES, WearLinkProtocol.CAP_STEPS, WearLinkProtocol.CAP_ASCENT,
        WearLinkProtocol.CAP_HRV,
    ),
    var storeId: String = "0123456789abcdef",
    /** The watch's clock, for the `OK` of a phone that asks for it. */
    var now: Long = 1_763_800_000_000L,
) : WearLinkRequestHandler {
    private class Row(val key: String, val time: Long, val line: String, val seq: Long)

    private val rows = ArrayList<Row>()
    private var seq = 0L

    /** Stores [line] at [time] under [key]; a row at the same time is replaced and numbered again. */
    @Synchronized
    fun put(key: String, time: Long, line: String) {
        rows.removeAll { it.key == key && it.time == time }
        rows += Row(key, time, line, ++seq)
    }

    override fun localName(): String = name
    override fun capabilities(): Set<String> = announced
    override fun nowMillis(): Long = now

    @Synchronized
    override fun seriesSince(key: String, cursor: String, limit: Int): WearLinkSince.Page? {
        if (key !in announced) return null
        val mine = rows.filter { it.key == key }.sortedBy { it.seq }
        val after = when (val position = WearLinkSince.position(cursor, storeId)) {
            is WearLinkSince.Position.AfterSeq -> position.seq
            is WearLinkSince.Position.AfterTime ->
                mine.firstOrNull { it.time > position.epochMillis }?.let { it.seq - 1 } ?: mine.lastOrNull()?.seq ?: 0L
        }
        val newer = mine.filter { it.seq > after }
        val taken = newer.take(limit)
        return WearLinkSince.Page(taken.map { it.line }, newer.size > limit, WearLinkSince.cursor(storeId, taken.lastOrNull()?.seq ?: after))
    }

    override fun heldPreferences(): Map<String, String> = preferences.toMap()
    override fun setPreference(key: String, value: String): Boolean {
        if (key !in preferences) return false
        preferences[key] = value
        return true
    }
    override fun workoutsSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkWorkout> {
        val newer = workouts.filter { it.startMillis > sinceEpochMillis }.sortedBy { it.startMillis }
        return Page(newer.take(limit), newer.size > limit)
    }
    override fun phoneActive(epochMillis: Long) {
        phoneActive += epochMillis
    }
    override fun liveHeartRate(seconds: Int, emit: (WearLinkProtocol.HeartRateSample) -> Unit): Int {
        live.forEach(emit)
        return live.size
    }
}
