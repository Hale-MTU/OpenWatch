package tech.mmarca.openwatch.wearlink

import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The hello of every pairing of this build with the builds before it, line for line as they
 * cross the wire (`>` phone to watch, `<` watch to phone). 0.4.0 and 0.4.1 answer the hello
 * alike: both speak version 4 only (`VERSION = MIN_VERSION = 4`), refuse any other with
 * `VERSION 4 4` before reading the token, and their phones say hello with version 4 and
 * `hr,sm,st`. Their parsers demand exact field counts, so the lines they receive here are
 * the ones they always received: a `VERSION` refusal of three fields, and nothing after it.
 */
class WearLinkVersionGoldenTest {

    private val token = WearLinkToken.generate()

    /** This build's phone, as `BluetoothWearOsNodePort` says hello. */
    private val newHello = WearLinkProtocol.Hello(WearLinkProtocol.VERSION, setOf(WearLinkProtocol.CAP_CLOCK, WearLinkProtocol.CAP_BODY_COMPOSITION), token, "Pixel 6 Pro")

    @Test
    fun `a new phone and a 0_4_0 or 0_4_1 watch end in the phone telling the wearer to update the watch`() {
        for (build in listOf("0.4.0", "0.4.1")) {
            val transcript = Transcript()
            val pipe = Pipe()
            val watch = thread(name = "watch $build") { playOldWatch(pipe.watch, transcript) }

            val outcome = WearLinkClient.hello(pipe.phone, newHello, reconnect = { fail("$build: no version in common, no second hello"); error("") })
            pipe.phone.close()
            watch.join(5_000)

            assertEquals(build, WearLinkOutcome.VersionMismatch(4, 4), outcome)
            assertEquals(build, listOf("> HELLO 5 bc,tm $token Pixel 6 Pro", "< VERSION 4 4"), transcript.lines)
            // The phone's own reading of it: nothing in common, the watch's range below this phone's.
            assertEquals(null, WearLinkClient.commonVersion(WearLinkClient.VERSIONS, 4..4))
        }
    }

    @Test
    fun `a 0_4_0 or 0_4_1 phone and a new watch end in the old phone telling the wearer to update the phone`() {
        for (build in listOf("0.4.0", "0.4.1")) {
            // Not even known to the watch: the version is answered before the token is looked at.
            val loopback = Loopback()
            val transcript = Transcript()
            val pipe = Pipe()
            var outcome: WearLinkServer.Outcome? = null
            val watch = thread(name = "watch for $build") { outcome = loopback.server.serve(pipe.watch) }

            val writer = LineWriter(pipe.phone.output)
            val reader = LineReader(pipe.phone.input)
            val hello = "HELLO 4 hr,sm,st $token Pixel 6 Pro"
            writer.line(hello)
            writer.flush()
            transcript.add("> $hello")
            reader.readLine()?.let { transcript.add("< $it") }
            pipe.phone.close()
            watch.join(5_000)

            assertEquals(build, listOf("> HELLO 4 hr,sm,st $token Pixel 6 Pro", "< VERSION 5 5"), transcript.lines)
            assertEquals(build, WearLinkServer.Outcome.VERSION_MISMATCH, outcome)
            assertEquals(build, emptyList<Any>(), loopback.trust.pending)
            // The old phone reads `VERSION <min> <max>`, three fields as it demands, and maps a lowest
            // version above its own 4 to "update the phone".
        }
    }

    @Test
    fun `a new phone and a new watch speak version 5, the clock goes to the phone that asked, and a series page follows`() {
        val loopback = Loopback().also { it.trust.tokens[it.phoneAddress] = token }
        loopback.handler.put("hr", 1_700_000_000_000L, "HR 1700000000000 61")
        val transcript = Transcript()
        val link = loopback.connect(transcript)

        val session = (WearLinkClient.hello(link.clientConnection, newHello) as WearLinkOutcome.Ok).value
        session.pullSince("hr", WearLinkSince.afterTime(0), WearLinkSince.MAX_LIMIT)
        session.ping()
        session.close()

        assertEquals(
            listOf(
                "> HELLO 5 bc,tm $token Pixel 6 Pro",
                "< OK 5 as,hr,hv,sm,st,tm 1763800000000 Galaxy Watch8 (89FZ)",
                "> SINCE hr t0 2000",
                "< HR 1700000000000 61",
                "< END 1 0 0123456789abcdef.1",
                "> PING",
                "< PONG",
            ),
            transcript.lines,
        )
        assertEquals(WearLinkServer.Outcome.SERVED, link.serverOutcome())
    }

    @Test
    fun `a phone a version ahead says hello again at the watch's highest, on a new connection`() {
        val loopback = Loopback(options = WearLinkServer.Options(versions = 5..5)).also { it.trust.tokens[it.phoneAddress] = token }
        val first = Transcript()
        val second = Transcript()
        val firstLink = loopback.connect(first)
        var again: Loopback.Link? = null

        val outcome = WearLinkClient.hello(firstLink.clientConnection, newHello, versions = 5..6, reconnect = {
            loopback.connect(second).also { again = it }.clientConnection
        })

        val session = (outcome as WearLinkOutcome.Ok).value
        assertEquals(5, session.peer.version)
        assertEquals(listOf("> HELLO 6 bc,tm $token Pixel 6 Pro", "< VERSION 5 5"), first.lines)
        assertEquals(WearLinkServer.Outcome.VERSION_MISMATCH, firstLink.serverOutcome())
        assertEquals("> HELLO 5 bc,tm $token Pixel 6 Pro", second.lines.first())
        assertTrue(second.lines[1].startsWith("< OK 5 "))
        session.close()
        assertEquals(WearLinkServer.Outcome.SERVED, again!!.serverOutcome())
    }

    @Test
    fun `a watch a version ahead speaks the phone's, and ranges that do not meet are refused, no second hello`() {
        val ahead = Loopback(options = WearLinkServer.Options(versions = 5..6)).also { it.trust.tokens[it.phoneAddress] = token }
        val transcript = Transcript()
        val session = (WearLinkClient.hello(ahead.connect(transcript).clientConnection, newHello, versions = 5..5) as WearLinkOutcome.Ok).value
        assertEquals(5, session.peer.version)
        assertTrue(transcript.lines[1].startsWith("< OK 5 "))
        session.close()

        val noSecond = { fail("no version in common, no second hello"); error("") }
        val older = Loopback(options = WearLinkServer.Options(versions = 5..5))
        val tooNew = older.connect()
        assertEquals(WearLinkOutcome.VersionMismatch(5, 5), WearLinkClient.hello(tooNew.clientConnection, newHello, versions = 6..6, reconnect = noSecond))
        tooNew.clientConnection.close()

        val newer = Loopback(options = WearLinkServer.Options(versions = 6..7))
        val tooOld = newer.connect()
        assertEquals(WearLinkOutcome.VersionMismatch(6, 7), WearLinkClient.hello(tooOld.clientConnection, newHello, versions = 5..5, reconnect = noSecond))
        tooOld.clientConnection.close()
    }

    @Test
    fun `the highest version both speak`() {
        assertEquals(5, WearLinkClient.commonVersion(4..6, 5..5))
        assertEquals(6, WearLinkClient.commonVersion(5..6, 4..8))
        assertEquals(null, WearLinkClient.commonVersion(5..5, 4..4))
        assertEquals(null, WearLinkClient.commonVersion(5..5, 6..7))
    }

    /**
     * The hello of a 0.4.0 or 0.4.1 watch: a hello it can parse, a version other than 4, so
     * `VERSION 4 4`, then it waits for the phone to close.
     */
    private fun playOldWatch(connection: WearLinkConnection, transcript: Transcript) {
        try {
            val line = LineReader(connection.input).readLine() ?: return
            transcript.add("> $line")
            val fields = line.split(' ')
            check(fields.size >= 4 && fields[0] == "HELLO") { "a 0.4.x watch would answer ERROR bad_hello" }
            if (fields[1].toIntOrNull() == 4) error("this phone said version 4")
            val reply = "VERSION 4 4"
            LineWriter(connection.output).apply { line(reply); flush() }
            transcript.add("< $reply")
            while (connection.input.read() >= 0) Unit
        } catch (_: IOException) {
            // The phone closed.
        }
    }

    /** Two ends of one connection over pipes; closing either closes both. */
    private class Pipe {
        private val toWatch = PipedOutputStream()
        private val toPhone = PipedOutputStream()
        private val watchInput = PipedInputStream(toWatch, BUFFER)
        private val phoneInput = PipedInputStream(toPhone, BUFFER)

        private fun closeAll() {
            runCatching { toWatch.close() }
            runCatching { toPhone.close() }
            runCatching { watchInput.close() }
            runCatching { phoneInput.close() }
        }

        val phone = WearLinkConnection(phoneInput, toWatch, "watch") { closeAll() }
        val watch = WearLinkConnection(watchInput, toPhone, "AA:BB:CC:DD:EE:01") { closeAll() }
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}
