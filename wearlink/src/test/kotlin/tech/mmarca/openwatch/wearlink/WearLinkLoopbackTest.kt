package tech.mmarca.openwatch.wearlink

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Client against server over piped streams: the whole lifecycle of a connection, without Bluetooth. */
class WearLinkLoopbackTest {

    private val token = WearLinkToken.generate()
    private fun hello(version: Int = WearLinkProtocol.VERSION, token: String = this.token) =
        WearLinkProtocol.Hello(version, setOf("hr", "sm"), token, "Pixel 6 Pro")

    private fun trusted(): Loopback = Loopback().also { it.trust.tokens[it.phoneAddress] = token }

    @Test
    fun `a trusted phone says hello, pings, and the watch returns once the phone closes`() {
        val loopback = trusted()
        val link = loopback.connect()

        val session = (WearLinkClient.hello(link.clientConnection, hello()) as WearLinkOutcome.Ok).value
        assertEquals("Galaxy Watch8 (89FZ)", session.peer.name)
        assertEquals(setOf("as", "hr", "hv", "sm", "st"), session.peer.capabilities)
        assertEquals(WearLinkOutcome.Ok(Unit), session.ping())
        assertFalse(link.serverReturned())
        session.close()

        assertEquals(WearLinkServer.Outcome.SERVED, link.serverOutcome())
        assertEquals(0, loopback.server.activeClients)
    }

    @Test
    fun `an unknown phone is pending, allowed, then served`() {
        val loopback = Loopback()
        val first = loopback.connect()

        assertEquals(WearLinkOutcome.Pending, WearLinkClient.hello(first.clientConnection, hello()))
        first.clientConnection.close()
        assertEquals(WearLinkServer.Outcome.PENDING, first.serverOutcome())
        assertEquals(listOf(Triple(loopback.phoneAddress, token, "Pixel 6 Pro")), loopback.trust.pending)

        loopback.trust.tokens[loopback.phoneAddress] = token
        val second = loopback.connect()
        assertTrue(WearLinkClient.hello(second.clientConnection, hello()) is WearLinkOutcome.Ok)
        second.clientConnection.close()
        assertEquals(WearLinkServer.Outcome.SERVED, second.serverOutcome())
    }

    @Test
    fun `a known phone with another token is asked about again and the old token still works, a blocked phone is blocked`() {
        val loopback = trusted()
        val newToken = WearLinkToken.generate()
        val reinstalled = loopback.connect()
        assertEquals(WearLinkOutcome.Pending, WearLinkClient.hello(reinstalled.clientConnection, hello(token = newToken)))
        reinstalled.clientConnection.close()
        assertEquals(WearLinkServer.Outcome.PENDING, reinstalled.serverOutcome())
        assertEquals(listOf(Triple(loopback.phoneAddress, newToken, "Pixel 6 Pro")), loopback.trust.pending)

        val old = loopback.connect()
        assertTrue(WearLinkClient.hello(old.clientConnection, hello()) is WearLinkOutcome.Ok)
        old.clientConnection.close()
        assertEquals(WearLinkServer.Outcome.SERVED, old.serverOutcome())

        loopback.trust.blocked += loopback.phoneAddress
        val blocked = loopback.connect()
        assertEquals(
            WearLinkOutcome.Unauthorized(WearLinkProtocol.UnauthorizedReason.BLOCKED),
            WearLinkClient.hello(blocked.clientConnection, hello()),
        )
        blocked.clientConnection.close()
    }

    @Test
    fun `an old phone learns the versions the watch speaks`() {
        val link = trusted().connect()

        assertEquals(WearLinkOutcome.VersionMismatch(5, 5), WearLinkClient.hello(link.clientConnection, hello(version = 4)))
        link.clientConnection.close()
        assertEquals(WearLinkServer.Outcome.VERSION_MISMATCH, link.serverOutcome())
    }

    @Test
    fun `a page of two thousand minutes arrives whole, and requests share one connection`() {
        val loopback = trusted()
        val watch = loopback.handler
        val minutes = List(2500) { index ->
            WearLinkProtocol.SleepMinute(1_700_000_000_000L + index * 60_000L, WearLinkProtocol.MinuteKind.RAW, 7200, 16, 300, 0, 58, 12, 6, intArrayOf(0, 0, 1000), intArrayOf(3, 3, 3), 88, 90, 1)
        }
        minutes.forEach { watch.put("sm", it.epochMillis, WearLinkProtocol.formatSleepMinute(it)) }
        repeat(10) { watch.put("hr", 1_700_000_000_000L + it * 10_000L, WearLinkProtocol.formatSample(1_700_000_000_000L + it * 10_000L, 60 + it)) }
        val link = loopback.connect()
        val session = (WearLinkClient.hello(link.clientConnection, hello()) as WearLinkOutcome.Ok).value

        val page = (session.pullSince("sm", WearLinkSince.afterTime(0), 2000) as WearLinkOutcome.Ok).value
        assertEquals(2000, page.lines.size)
        assertTrue(page.more)
        val rest = (session.pullSince("sm", page.cursor, 2000) as WearLinkOutcome.Ok).value
        assertEquals(minutes.drop(2000), rest.lines.map { WearLinkProtocol.parseSleepMinute(it) })
        assertFalse(rest.more)
        val heart = (session.pullSince("hr", WearLinkSince.afterTime(0), 2000) as WearLinkOutcome.Ok).value
        assertEquals(10, heart.lines.mapNotNull(WearLinkProtocol::parseSample).size)
        assertEquals(WearLinkOutcome.Ok(Unit), session.notifyPhoneActive(1_700_000_200_000L))
        assertEquals(WearLinkOutcome.Ok(mapOf("hr_day" to "10")), session.pullPreferences())
        assertEquals(WearLinkOutcome.Ok(Unit), session.setPreference("hr_day", "600"))
        assertEquals(WearLinkOutcome.Ok(mapOf("hr_day" to "600")), session.pullPreferences())
        assertTrue(session.setPreference("nope", "1") !is WearLinkOutcome.Ok)
        assertEquals(listOf(1_700_000_200_000L), watch.phoneActive)
        assertFalse(link.serverReturned())
        session.close()
        assertEquals(WearLinkServer.Outcome.SERVED, link.serverOutcome())
    }

    @Test
    fun `a row rewritten in place or written late comes again after the cursor`() {
        val loopback = trusted()
        val watch = loopback.handler
        watch.put("st", 120_000L, "ST 120000 10")
        watch.put("st", 180_000L, "ST 180000 20")
        val link = loopback.connect()
        val session = (WearLinkClient.hello(link.clientConnection, hello()) as WearLinkOutcome.Ok).value
        val first = (session.pullSince("st", WearLinkSince.afterTime(0), 10) as WearLinkOutcome.Ok).value
        assertEquals(WearLinkSince.Page(listOf("ST 120000 10", "ST 180000 20"), false, "0123456789abcdef.2"), first)

        watch.put("st", 180_000L, "ST 180000 35")
        watch.put("st", 60_000L, "ST 60000 5")
        val next = (session.pullSince("st", first.cursor, 10) as WearLinkOutcome.Ok).value

        assertEquals(WearLinkSince.Page(listOf("ST 180000 35", "ST 60000 5"), false, "0123456789abcdef.4"), next)
        // Nothing new: the same cursor back.
        assertEquals(WearLinkSince.Page(emptyList(), false, next.cursor), (session.pullSince("st", next.cursor, 10) as WearLinkOutcome.Ok).value)
        session.close()
    }

    @Test
    fun `a time position hands a time cursor over to the sequence`() {
        val loopback = trusted()
        val watch = loopback.handler
        // As the version 3 store numbers the rows it kept: in time order.
        for (at in 1..4) watch.put("hr", at * 10_000L, "HR ${at * 10_000L} ${60 + at}")
        // Written after them, behind the phone's time cursor: a late row the time cursor would never have sent.
        watch.put("hr", 15_000L, "HR 15000 70")
        val link = loopback.connect()
        val session = (WearLinkClient.hello(link.clientConnection, hello()) as WearLinkOutcome.Ok).value

        // The phone had pulled up to 20 000: the rows newer than that come, then everything written after them.
        val page = (session.pullSince("hr", WearLinkSince.afterTime(20_000L), 10) as WearLinkOutcome.Ok).value
        assertEquals(listOf("HR 30000 63", "HR 40000 64", "HR 15000 70"), page.lines)
        // A time past every row starts after the newest: nothing again, and what comes later comes.
        val caughtUp = (session.pullSince("hr", WearLinkSince.afterTime(90_000L), 10) as WearLinkOutcome.Ok).value
        assertEquals(WearLinkSince.Page(emptyList(), false, "0123456789abcdef.5"), caughtUp)
        session.close()
    }

    @Test
    fun `another store's cursor starts over, and a key the watch lacks is refused on a connection that goes on`() {
        val loopback = trusted()
        loopback.handler.put("hv", 1_000L, "HV 1000 452 280")
        val link = loopback.connect()
        val session = (WearLinkClient.hello(link.clientConnection, hello()) as WearLinkOutcome.Ok).value

        // The watch's data was cleared: its store has a new id, and the phone's cursor is of the old one.
        val page = (session.pullSince("hv", "fedcba9876543210.99", 10) as WearLinkOutcome.Ok).value
        assertEquals(listOf("HV 1000 452 280"), page.lines)
        assertEquals(WearLinkOutcome.Refused(WearLinkProtocol.ErrorCode.BAD_REQUEST), session.pullSince("o2", WearLinkSince.afterTime(0), 10))
        assertEquals(WearLinkOutcome.Ok(Unit), session.ping())
        session.close()
        assertEquals(WearLinkServer.Outcome.SERVED, link.serverOutcome())
    }

    @Test
    fun `a stored line that would not travel as one line is left out, and the cursor moves past it`() {
        val loopback = trusted()
        loopback.handler.put("hr", 1_000L, "HR 1000 60\nEND 9 0")
        loopback.handler.put("hr", 2_000L, "HR 2000 61")
        val link = loopback.connect()
        val session = (WearLinkClient.hello(link.clientConnection, hello()) as WearLinkOutcome.Ok).value

        assertEquals(
            WearLinkOutcome.Ok(WearLinkSince.Page(listOf("HR 2000 61"), false, "0123456789abcdef.2")),
            session.pullSince("hr", WearLinkSince.afterTime(0), 10),
        )
        session.close()
    }

    @Test
    fun `live windows hand out each reading and can repeat on one connection`() {
        val loopback = trusted()
        val readings = List(3) { WearLinkProtocol.HeartRateSample(1_700_000_000_000L + it * 1000L, 70 + it) }
        loopback.handler.live = readings
        val link = loopback.connect()
        val session = (WearLinkClient.hello(link.clientConnection, hello()) as WearLinkOutcome.Ok).value

        val seen = ArrayList<WearLinkProtocol.HeartRateSample>()
        assertEquals(3, (session.liveHeartRate(5) { seen += it } as WearLinkOutcome.Ok).value)
        assertEquals(readings, seen)
        // Off the wrist: the window ends at once, empty, and the connection stays usable.
        loopback.handler.live = emptyList()
        assertEquals(0, (session.liveHeartRate(5) { seen += it } as WearLinkOutcome.Ok).value)
        assertEquals(3, seen.size)
        session.close()
        assertEquals(WearLinkServer.Outcome.SERVED, link.serverOutcome())
    }

    @Test
    fun `a phone that never says hello is cut off, and one that goes quiet is too`() {
        // A closed pipe reads as end of stream where a closed socket throws; either way the watchdog ended it early.
        val quick = Loopback(options = WearLinkServer.Options(helloTimeoutMillis = 200, idleTimeoutMillis = 200))
        quick.trust.tokens[quick.phoneAddress] = token
        val started = System.currentTimeMillis()
        val silent = quick.connect()
        val silentOutcome = silent.serverOutcome(seconds = 3)
        assertTrue("$silentOutcome", silentOutcome == WearLinkServer.Outcome.NO_HELLO || silentOutcome == WearLinkServer.Outcome.PROTOCOL_ERROR)
        assertTrue(System.currentTimeMillis() - started < 2_000)

        val idle = quick.connect()
        val session = (WearLinkClient.hello(idle.clientConnection, hello()) as WearLinkOutcome.Ok).value
        val idleOutcome = idle.serverOutcome(seconds = 3)
        assertEquals(WearLinkServer.Outcome.IDLE, idleOutcome)
        assertEquals(WearLinkOutcome.Closed, session.ping())
    }

    @Test
    fun `a bad hello and a bad request are named, and the connection survives a bad request`() {
        val loopback = trusted()
        val bad = loopback.connect()
        LineWriter(bad.clientConnection.output).apply { line("PING"); flush() }
        assertEquals("ERROR bad_hello", LineReader(bad.clientConnection.input).readLine())
        bad.clientConnection.close()
        assertEquals(WearLinkServer.Outcome.BAD_HELLO, bad.serverOutcome())

        val link = loopback.connect()
        val session = (WearLinkClient.hello(link.clientConnection, hello()) as WearLinkOutcome.Ok).value
        LineWriter(link.clientConnection.output).apply { line("WHAT"); flush() }
        assertEquals("ERROR bad_request", LineReader(link.clientConnection.input).readLine())
        session.close()
        assertEquals(WearLinkServer.Outcome.SERVED, link.serverOutcome())
    }

    @Test
    fun `a line over the limit ends the connection`() {
        val link = trusted().connect()
        LineWriter(link.clientConnection.output).apply { line("HELLO " + "x".repeat(600)); flush() }

        assertEquals(WearLinkServer.Outcome.PROTOCOL_ERROR, link.serverOutcome())
    }

    @Test
    fun `a third phone at once is told the watch is busy`() {
        val loopback = Loopback(options = WearLinkServer.Options(maxClients = 2))
        loopback.trust.tokens[loopback.phoneAddress] = token
        val one = loopback.connect()
        val two = loopback.connect()
        val first = (WearLinkClient.hello(one.clientConnection, hello()) as WearLinkOutcome.Ok).value
        val second = (WearLinkClient.hello(two.clientConnection, hello()) as WearLinkOutcome.Ok).value
        val three = loopback.connect()

        assertEquals(WearLinkOutcome.Refused(WearLinkProtocol.ErrorCode.BUSY), WearLinkClient.hello(three.clientConnection, hello()))
        three.clientConnection.close()
        assertEquals(WearLinkServer.Outcome.BUSY, three.serverOutcome())
        first.close()
        second.close()
        assertEquals(WearLinkServer.Outcome.SERVED, one.serverOutcome())
        assertEquals(WearLinkServer.Outcome.SERVED, two.serverOutcome())
    }

    @Test
    fun `the watch closes a refusal only after the phone did, so the line is never dropped`() {
        val loopback = Loopback(options = WearLinkServer.Options(refusalCloseWaitMillis = 300))
        val link = loopback.connect()

        assertEquals(WearLinkOutcome.Pending, WearLinkClient.hello(link.clientConnection, hello()))
        Thread.sleep(100)
        assertFalse(link.serverReturned())
        link.clientConnection.close()
        assertEquals(WearLinkServer.Outcome.PENDING, link.serverOutcome())
    }

    @Test
    fun `hello and reply lines round-trip through the writer`() {
        val out = ByteArrayOutputStream()
        LineWriter(out).apply { line(WearLinkProtocol.formatHello(hello())); flush() }

        assertTrue(out.toString(Charsets.UTF_8).startsWith("HELLO 5 hr,sm $token Pixel 6 Pro"))
    }

    @Test
    fun `workouts arrive whole with their pauses and route, and a page says when more remain`() {
        val loopback = trusted()
        val t = 1_700_000_000_000L
        val route = List(300) { WearLinkWorkout.RoutePoint(t + it * 5_000L, 475_000_000 + it * 100, 112_000_000 - it * 50, 5_340 + it, if (it % 2 == 0) 80 else null) }
        val run = WearLinkWorkout(t, t + 1_500_000L, 56, 4000, 3_210, 420, listOf(WearLinkWorkout.Pause(t + 600_000L, t + 660_000L)), route)
        val bike = WearLinkWorkout(t + 10_000_000L, t + 12_000_000L, 8, null, 15_000, null)
        val gym = WearLinkWorkout(t + 20_000_000L, t + 21_000_000L, 70, null, null, null)
        loopback.handler.workouts = listOf(gym, run, bike)
        val link = loopback.connect()
        val session = (WearLinkClient.hello(link.clientConnection, hello()) as WearLinkOutcome.Ok).value

        val first = (session.pullWorkouts(0, 2) as WearLinkOutcome.Ok).value
        assertEquals(listOf(run, bike), first.items)
        assertTrue(first.more)
        val rest = (session.pullWorkouts(first.items.last().startMillis, 2) as WearLinkOutcome.Ok).value
        assertEquals(listOf(gym), rest.items)
        assertFalse(rest.more)
        session.close()
    }
}
