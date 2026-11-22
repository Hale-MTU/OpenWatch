package tech.mmarca.openwatch.devices.wearos

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.core.performance.DefaultDispatcherProvider
import tech.mmarca.openwatch.core.security.testSeal
import tech.mmarca.openwatch.devices.FakeSharedPreferences
import tech.mmarca.openwatch.wearlink.Page
import tech.mmarca.openwatch.wearlink.WearLinkConnection
import tech.mmarca.openwatch.wearlink.WearLinkProtocol
import tech.mmarca.openwatch.wearlink.WearLinkRequestHandler
import tech.mmarca.openwatch.wearlink.WearLinkServer
import tech.mmarca.openwatch.wearlink.WearLinkTrustStore
import tech.mmarca.openwatch.wearlink.WearLinkWorkout

/**
 * The port over a watch in the same JVM: the real `:wearlink` server on piped streams in place
 * of the RFCOMM socket, so what is counted is connections, hellos and requests as the watch sees
 * them. Like RFCOMM, the loopback takes one connection at a time. The session windows run on a
 * clock the test moves.
 */
class BluetoothWearOsNodePortTest {

    private val linkStore = WearOsLinkStore(FakeSharedPreferences(), testSeal())

    @Volatile
    private var now = 0L
    private val idle = TestCoroutineScheduler()
    private var watch = LoopbackWatch(linkStore.tokenFor(REGISTERED, BOND), FULL)
    private val sessions = WearOsSessionCache(CoroutineScope(StandardTestDispatcher(idle)), clock = { now })
    private val port by lazy { BluetoothWearOsNodePort(linkStore, watch, DefaultDispatcherProvider, sessions) }

    @After
    fun closeLinks() = watch.links.forEach { it.dropOnWatch() }

    private suspend fun pullHeartRate() = port.pullHeartRate(REGISTERED, NAME, Instant.EPOCH)

    /** One page of a sync: the one place the link-owner tests name a series request. */
    private suspend fun syncPage() = port.pullHeartRate(REGISTERED, NAME, Instant.EPOCH)

    /** The live heart rate in a coroutine of its own, as the watch screen collects it. */
    private fun CoroutineScope.streamLive(): Job = launch(Dispatchers.IO) { port.liveHeartRate(REGISTERED, NAME) {} }

    /** Waits, in real time, for what threads other than the test's bring about. */
    private fun awaitTrue(what: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!what()) {
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.sleep(2)
        }
    }

    @Test
    fun `a sync's requests share one connection and one hello`() = runTest {
        watch.data.heartRatePages += Page(listOf(WearLinkProtocol.HeartRateSample(T0, 61)), more = true)
        watch.data.heartRatePages += Page(listOf(WearLinkProtocol.HeartRateSample(T0 + 10_000, 62)), more = false)

        // One sync, in the order WearOsWatchSyncService asks; each call used to connect and say hello on its own.
        coroutineScope {
            val first = pullHeartRate()!!
            assertTrue(first.hasMore)
            assertEquals(62, port.pullHeartRate(REGISTERED, NAME, first.samples.last().time)!!.samples.single().beatsPerMinute)
            port.pullSteps(REGISTERED, NAME, Instant.EPOCH)
            port.pullAscent(REGISTERED, NAME, Instant.EPOCH)
            port.pullSleepMinutes(REGISTERED, NAME, Instant.EPOCH)
            port.pullHrv(REGISTERED, NAME, Instant.EPOCH)
            port.pullRespiratory(REGISTERED, NAME, Instant.EPOCH)
            port.pullSpo2(REGISTERED, NAME, Instant.EPOCH)
            port.pullSkin(REGISTERED, NAME, Instant.EPOCH)
            port.pullWorkouts(REGISTERED, NAME, Instant.EPOCH)
            assertTrue(port.setWatchSetting(REGISTERED, NAME, WearLinkProtocol.Preferences.HEART_RATE_MAX, "190"))
        }

        assertEquals(1, watch.connects.get())
        assertEquals(1, watch.data.hellos.get())
        assertEquals(listOf("HR", "HR", "ST", "AS", "SM", "HV", "RR", "O2", "SK", "WO", "SET"), watch.data.asked)
        assertTrue(watch.links.single().isOpen)
        // The sync is over, so is its session: the phone closes it, as the watch waits for.
        idle.runCurrent()
        assertEquals(WearLinkServer.Outcome.SERVED, watch.links.single().outcome())
    }

    @Test
    fun `capabilities are read once per sync, and a series the watch lacks is never asked for`() = runTest {
        watch = LoopbackWatch(linkStore.tokenFor(REGISTERED, BOND), setOf(WearLinkProtocol.CAP_HEART_RATE, WearLinkProtocol.CAP_SLEEP_MINUTES))

        coroutineScope {
            pullHeartRate()
            assertEquals(WearOsStepPage(emptyList(), false), port.pullSteps(REGISTERED, NAME, Instant.EPOCH))
            assertEquals(WearOsAscentPage(emptyList(), false), port.pullAscent(REGISTERED, NAME, Instant.EPOCH))
            assertEquals(WearOsHrvPage(emptyList(), false), port.pullHrv(REGISTERED, NAME, Instant.EPOCH))
            assertEquals(WearOsSpo2Page(emptyList(), false), port.pullSpo2(REGISTERED, NAME, Instant.EPOCH))
            assertEquals(WearOsWorkoutPage(emptyList(), false), port.pullWorkouts(REGISTERED, NAME, Instant.EPOCH))
            assertNull(port.pullWatchSettings(REGISTERED, NAME))
            assertFalse(port.setWatchSetting(REGISTERED, NAME, WearLinkProtocol.Preferences.HEART_RATE_MAX, "190"))
            assertFalse(port.notifyPhoneActive(REGISTERED, NAME, Instant.ofEpochMilli(T0)))
        }

        assertEquals(1, watch.data.hellos.get())
        assertEquals(listOf("HR"), watch.data.asked)
    }

    @Test
    fun `a session idle for ten seconds is closed and the next request opens another`() = runTest {
        pullHeartRate()
        now += 9_999
        pullHeartRate()
        assertEquals(1, watch.connects.get())

        now += 10_000
        pullHeartRate()

        assertEquals(2, watch.connects.get())
        // Closed by the phone, before the watch's own idle timeout.
        assertEquals(WearLinkServer.Outcome.SERVED, watch.links[0].outcome())
        assertTrue(watch.links[1].isOpen)
    }

    @Test
    fun `a series the watch lacks sends nothing, so it does not count as use`() = runTest {
        watch = LoopbackWatch(linkStore.tokenFor(REGISTERED, BOND), setOf(WearLinkProtocol.CAP_HEART_RATE, WearLinkProtocol.CAP_SLEEP_MINUTES))
        pullHeartRate()
        now += 8_000
        port.pullHrv(REGISTERED, NAME, Instant.EPOCH)

        // Ten seconds after the last request the watch heard, not two after the skipped one.
        now += 2_000
        pullHeartRate()

        assertEquals(2, watch.connects.get())
    }

    @Test
    fun `a session is opened again once it is 540 seconds old`() = runTest {
        pullHeartRate()
        repeat(107) {
            now += 5_000
            pullHeartRate()
        }
        assertEquals(1, watch.connects.get())

        now += 5_000
        pullHeartRate()

        assertEquals(2, watch.connects.get())
        assertEquals(WearLinkServer.Outcome.SERVED, watch.links[0].outcome())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a session nobody uses is closed when its idle window ends`() = runTest {
        pullHeartRate()

        idle.advanceTimeBy(9_999)
        idle.runCurrent()
        assertTrue(watch.links.single().isOpen)
        idle.advanceTimeBy(1)
        idle.runCurrent()

        assertEquals(WearLinkServer.Outcome.SERVED, watch.links.single().outcome())
        now += 10_000
        pullHeartRate()
        assertEquals(2, watch.connects.get())
    }

    @Test
    fun `a session the watch closed is replaced by one new connection, unseen by the caller`() = runTest {
        pullHeartRate()
        watch.links[0].dropOnWatch()
        watch.data.heartRatePages += Page(listOf(WearLinkProtocol.HeartRateSample(T0, 64)), more = false)

        val page = pullHeartRate()

        assertEquals(64, page!!.samples.single().beatsPerMinute)
        assertEquals(2, watch.connects.get())
        assertEquals(2, watch.data.hellos.get())
    }

    @Test
    fun `a closed session reconnects once, then fails as before`() = runTest {
        pullHeartRate()
        watch.links[0].dropOnWatch()
        watch.refusedConnects.set(1)

        val failure = runCatching { pullHeartRate() }.exceptionOrNull()

        assertTrue(failure is WearOsLinkFailure.NoAnswer)
        assertEquals(2, watch.connects.get())
        // Nothing stale is left behind: the caller's own retry connects afresh.
        pullHeartRate()
        assertEquals(3, watch.connects.get())
        assertEquals(2, watch.data.hellos.get())
    }

    @Test
    fun `the live heart rate asks five-second windows on the watch's one connection`() = runTest {
        assertEquals(emptyMap<String, String>(), port.pullWatchSettings(REGISTERED, NAME))

        // Off the wrist: two windows in a row without a reading end the stream.
        assertTrue(port.liveHeartRate(REGISTERED, NAME) {})
        assertTrue(port.setWatchSetting(REGISTERED, NAME, WearLinkProtocol.Preferences.HEART_RATE_MAX, "190"))

        assertEquals(1, watch.connects.get())
        assertEquals(listOf("PREF", "LIVE", "LIVE", "SET"), watch.data.asked)
        assertEquals(listOf(5, 5), watch.data.liveSeconds)
    }

    @Test
    fun `a status check made while live heart rate streams succeeds`() = runTest {
        watch.data.liveStreaming = true
        val live = streamLive()
        awaitTrue { watch.data.liveWindows.get() > 0 }

        // From a coroutine of its own that ends with the check, as the watch screen's does.
        val status = withContext(Dispatchers.IO) { port.checkStatus(REGISTERED, NAME) }

        assertEquals(WearOsAppStatus.APP_RUNNING, status.appStatus)
        // The stream goes on after the check, on the same connection.
        val checkedAt = watch.data.asked.indexOf("PING")
        awaitTrue { watch.data.asked.lastIndexOf("LIVE") > checkedAt }
        live.cancelAndJoin()
        assertEquals(1, watch.connects.get())
        assertEquals(0, watch.secondConnects.get())
    }

    @Test
    fun `a sync made while live heart rate streams succeeds without a retry`() = runTest {
        watch.data.liveStreaming = true
        val live = streamLive()
        awaitTrue { watch.data.liveWindows.get() > 0 }

        // One sync in a coroutine of its own: its pages, then a setting, as the zones end it.
        withContext(Dispatchers.IO) {
            repeat(SYNC_PAGES) { assertNotNull(syncPage()) }
            assertTrue(port.setWatchSetting(REGISTERED, NAME, WearLinkProtocol.Preferences.HEART_RATE_MAX, "190"))
        }

        val asked = watch.data.asked.toList()
        val sync = asked.subList(asked.indexOf(SYNC_PAGE_WORD), asked.lastIndexOf("SET") + 1)
        // Each request asked once, and no live window between them: the stream waits for the sync.
        assertEquals(List(SYNC_PAGES) { SYNC_PAGE_WORD } + "SET", sync)
        // Once the sync is over, the stream comes back.
        awaitTrue { watch.data.asked.lastIndexOf("LIVE") > asked.lastIndexOf("SET") }
        live.cancelAndJoin()
        assertEquals(1, watch.connects.get())
        assertEquals(0, watch.secondConnects.get())
    }

    @Test
    fun `two concurrent calls never hold two connections`() = runTest {
        val release = CountDownLatch(1)
        watch.data.holdPreferences = release

        withContext(Dispatchers.IO) {
            val settings = async { port.pullWatchSettings(REGISTERED, NAME) }
            awaitTrue { "PREF" in watch.data.asked }
            val status = async { port.checkStatus(REGISTERED, NAME) }
            Thread.sleep(200)
            // The check waits for the link instead of opening a second connection.
            assertFalse(status.isCompleted)
            assertEquals(1, watch.connects.get())

            release.countDown()
            assertEquals(emptyMap<String, String>(), settings.await())
            assertEquals(WearOsAppStatus.APP_RUNNING, status.await().appStatus)
        }

        assertEquals(1, watch.connects.get())
        assertEquals(1, watch.mostOpen.get())
        assertEquals(0, watch.secondConnects.get())
    }

    @Test
    fun `a call that waits past its timeout fails as no answer and connects nothing`() = runTest {
        val release = CountDownLatch(1)
        watch.data.holdPreferences = release

        withContext(Dispatchers.IO) {
            val settings = async { port.pullWatchSettings(REGISTERED, NAME) }
            awaitTrue { "PREF" in watch.data.asked }
            val failure = try {
                sessions.request<Unit>(BOND, "$BOND $REGISTERED", owner = null, timeoutMillis = 100, capability = null, open = { error("a second connection") }) {
                    error("asked on a link another call holds")
                }
                null
            } catch (expected: WearOsLinkFailure) {
                expected
            }
            assertTrue(failure is WearOsLinkFailure.NoAnswer)

            release.countDown()
            settings.await()
        }

        assertEquals(1, watch.connects.get())
        assertEquals(listOf("PREF"), watch.data.asked)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a caller quiet for ten seconds no longer holds the live heart rate back`() = runTest {
        // Asked from the test's own coroutine, which runs on like a long-lived caller.
        port.pullWatchSettings(REGISTERED, NAME)
        watch.data.liveStreaming = true
        val live = streamLive()
        Thread.sleep(100)
        assertEquals(0, watch.data.liveWindows.get())

        now += 10_000
        idle.advanceTimeBy(10_000)
        idle.runCurrent()

        awaitTrue { watch.data.liveWindows.get() > 0 }
        live.cancelAndJoin()
        assertEquals(0, watch.secondConnects.get())
    }

    /**
     * A bonded watch whose each connection is served by the real server on a thread of its own.
     * Like RFCOMM, it takes one connection at a time: while one is open, the next connect fails
     * as the phone's stack fails it, with no answer.
     */
    private class LoopbackWatch(token: String, capabilities: Set<String>) : WearOsRadio {
        val data = WatchData(capabilities)
        private val server = WearLinkServer(TrustingStore(token), data)
        val links: MutableList<Link> = CopyOnWriteArrayList()

        /** Connections tried, refused ones included. */
        val connects = AtomicInteger()
        val refusedConnects = AtomicInteger()

        /** Connects tried while another connection was open, each refused. */
        val secondConnects = AtomicInteger()

        /** The most connections open at once. */
        val mostOpen = AtomicInteger()

        override fun bondedWatches(): List<BondedWatch> = listOf(BondedWatch(BOND, NAME))

        override fun localName(): String = "Pixel 6 Pro"

        @Synchronized
        override fun connect(watch: BondedWatch, guard: WearOsLinkGuard): WearLinkConnection {
            connects.incrementAndGet()
            if (refusedConnects.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) throw WearOsLinkFailure.NoAnswer()
            if (links.any { !it.closed }) {
                secondConnects.incrementAndGet()
                throw WearOsLinkFailure.NoAnswer()
            }
            val link = Link()
            links += link
            mostOpen.accumulateAndGet(links.count { !it.closed }) { a, b -> maxOf(a, b) }
            guard.watch { link.phone.close() }
            return link.phone
        }

        /** One connection. Closing either end closes every pipe, as a closed socket ends both sides' reads. */
        inner class Link {
            private val toWatch = PipedOutputStream()
            private val toPhone = PipedOutputStream()
            private val watchInput = PipedInputStream(toWatch, BUFFER)
            private val phoneInput = PipedInputStream(toPhone, BUFFER)
            private val outcome = AtomicReference<WearLinkServer.Outcome?>()
            private val served = CountDownLatch(1)

            /** Closed by either end; the radio takes another connection then. */
            @Volatile
            var closed = false
                private set

            private fun closeAll() {
                closed = true
                runCatching { toWatch.close() }
                runCatching { toPhone.close() }
                runCatching { watchInput.close() }
                runCatching { phoneInput.close() }
            }

            val phone = WearLinkConnection(phoneInput, toWatch, BOND) { closeAll() }
            private val watchEnd = WearLinkConnection(watchInput, toPhone, PHONE) { closeAll() }

            init {
                Thread({
                    outcome.set(server.serve(watchEnd))
                    served.countDown()
                }, "loopback-watch").apply {
                    isDaemon = true
                    start()
                }
            }

            val isOpen: Boolean get() = served.count > 0

            /** How the watch's end of the connection ended, once it has. */
            fun outcome(): WearLinkServer.Outcome? {
                served.await(5, TimeUnit.SECONDS)
                return outcome.get()
            }

            /** The watch's end goes away, as when its app restarts; the phone finds out at its next request. */
            fun dropOnWatch() {
                watchEnd.close()
                served.await(5, TimeUnit.SECONDS)
            }
        }
    }

    private class TrustingStore(private val token: String) : WearLinkTrustStore {
        override fun tokenFor(peerAddress: String): String = token
        override fun isBlocked(peerAddress: String): Boolean = false
        override fun notePending(peerAddress: String, token: String, peerName: String) = Unit
    }

    /** What the watch holds, and every request it was asked, by word. */
    private class WatchData(private val announced: Set<String>) : WearLinkRequestHandler {
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** The server reads the capabilities once for each hello it answers `OK`. */
        val hellos = AtomicInteger()
        val heartRatePages = ArrayDeque<Page<WearLinkProtocol.HeartRateSample>>()

        private fun <T> asked(word: String, page: Page<T> = Page(emptyList(), false)): Page<T> = page.also { asked += word }

        override fun localName(): String = NAME
        override fun capabilities(): Set<String> = announced.also { hellos.incrementAndGet() }
        override fun heartRateSince(sinceEpochMillis: Long, limit: Int) =
            asked("HR", synchronized(heartRatePages) { heartRatePages.removeFirstOrNull() } ?: Page(emptyList(), false))
        override fun sleepMinutesSince(sinceEpochMillis: Long, limit: Int) = asked<WearLinkProtocol.SleepMinute>("SM")
        override fun stepsSince(sinceEpochMillis: Long, limit: Int) = asked<WearLinkProtocol.StepMinute>("ST")
        override fun ascentSince(sinceEpochMillis: Long, limit: Int) = asked<WearLinkProtocol.AscentMinute>("AS")
        override fun hrvSince(sinceEpochMillis: Long, limit: Int) = asked<WearLinkProtocol.HrvWindow>("HV")
        override fun respiratorySince(sinceEpochMillis: Long, limit: Int) = asked<WearLinkProtocol.RespiratoryWindow>("RR")
        override fun spo2Since(sinceEpochMillis: Long, limit: Int) = asked<WearLinkProtocol.Spo2Measurement>("O2")
        override fun skinSince(sinceEpochMillis: Long, limit: Int) = asked<WearLinkProtocol.SkinMinute>("SK")
        override fun workoutsSince(sinceEpochMillis: Long, limit: Int) = asked<WearLinkWorkout>("WO")
        override fun phoneActive(epochMillis: Long) {
            asked += "PA"
        }
        /** When set, a settings read waits for it, as a request that takes a while. */
        @Volatile
        var holdPreferences: CountDownLatch? = null

        override fun heldPreferences(): Map<String, String> {
            asked += "PREF"
            holdPreferences?.await(5, TimeUnit.SECONDS)
            return emptyMap()
        }

        override fun setPreference(key: String, value: String): Boolean = true.also { asked += "SET" }

        /** While set, each live window sends a reading and lasts [LIVE_WINDOW_MILLIS]; otherwise the watch is off the wrist. */
        @Volatile
        var liveStreaming = false
        val liveWindows = AtomicInteger()
        val liveSeconds: MutableList<Int> = Collections.synchronizedList(mutableListOf())

        override fun liveHeartRate(seconds: Int, emit: (WearLinkProtocol.HeartRateSample) -> Unit): Int {
            asked += "LIVE"
            liveSeconds += seconds
            liveWindows.incrementAndGet()
            // Off the wrist, the watch answers none at once.
            if (!liveStreaming) return 0
            emit(WearLinkProtocol.HeartRateSample(T0, 70))
            Thread.sleep(LIVE_WINDOW_MILLIS)
            return 1
        }
    }

    private companion object {
        const val REGISTERED = "AA:BB:CC:DD:EE:01"
        const val BOND = "11:22:33:44:55:66"
        const val PHONE = "AA:BB:CC:DD:EE:99"
        const val NAME = "Galaxy Watch8"
        const val T0 = 1_760_000_000_000L
        const val BUFFER = 256 * 1024

        /** A live window in the loopback: long enough for a call to come during one. */
        const val LIVE_WINDOW_MILLIS = 30L

        /** What [syncPage] asks the watch, by word, and how many pages a sync takes here. */
        const val SYNC_PAGE_WORD = "HR"
        const val SYNC_PAGES = 4
        val FULL = setOf(
            WearLinkProtocol.CAP_HEART_RATE, WearLinkProtocol.CAP_SLEEP_MINUTES, WearLinkProtocol.CAP_STEPS,
            WearLinkProtocol.CAP_ASCENT, WearLinkProtocol.CAP_HRV, WearLinkProtocol.CAP_RESPIRATORY_RATE,
            WearLinkProtocol.CAP_SPO2, WearLinkProtocol.CAP_SKIN_TEMPERATURE, WearLinkProtocol.CAP_WORKOUTS,
            WearLinkProtocol.CAP_PREFERENCES, WearLinkProtocol.CAP_LIVE_HEART_RATE, WearLinkProtocol.CAP_PHONE_ACTIVE,
        )
    }
}
