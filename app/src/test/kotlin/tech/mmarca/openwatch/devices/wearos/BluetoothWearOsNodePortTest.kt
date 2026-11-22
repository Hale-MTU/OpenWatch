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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * them. The session windows run on a clock the test moves.
 */
class BluetoothWearOsNodePortTest {

    private val linkStore = WearOsLinkStore(FakeSharedPreferences(), testSeal())
    private var now = 0L
    private val idle = TestCoroutineScheduler()
    private var watch = LoopbackWatch(linkStore.tokenFor(REGISTERED, BOND), FULL)
    private val port by lazy {
        BluetoothWearOsNodePort(
            linkStore,
            watch,
            DefaultDispatcherProvider,
            WearOsSessionCache(CoroutineScope(StandardTestDispatcher(idle)), clock = { now }),
        )
    }

    @After
    fun closeLinks() = watch.links.forEach { it.dropOnWatch() }

    private suspend fun pullHeartRate() = port.pullHeartRate(REGISTERED, NAME, Instant.EPOCH)

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
    fun `a live heart rate stream holds a connection of its own`() = runTest {
        pullHeartRate()

        assertTrue(port.liveHeartRate(REGISTERED, NAME) {})

        assertEquals(WearLinkServer.Outcome.SERVED, watch.links[1].outcome())
        pullHeartRate()
        assertEquals(2, watch.connects.get())
        assertEquals(listOf("HR", "LIVE", "HR"), watch.data.asked)
    }

    /** A bonded watch whose each connection is served by the real server on a thread of its own. */
    private class LoopbackWatch(token: String, capabilities: Set<String>) : WearOsRadio {
        val data = WatchData(capabilities)
        private val server = WearLinkServer(TrustingStore(token), data)
        val links: MutableList<Link> = CopyOnWriteArrayList()

        /** Connections tried, refused ones included. */
        val connects = AtomicInteger()
        val refusedConnects = AtomicInteger()

        override fun bondedWatches(): List<BondedWatch> = listOf(BondedWatch(BOND, NAME))

        override fun localName(): String = "Pixel 6 Pro"

        override fun connect(watch: BondedWatch, guard: WearOsLinkGuard): WearLinkConnection {
            connects.incrementAndGet()
            if (refusedConnects.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) throw WearOsLinkFailure.NoAnswer()
            val link = Link()
            links += link
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

            private fun closeAll() {
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
        override fun heldPreferences(): Map<String, String> = emptyMap<String, String>().also { asked += "PREF" }
        override fun setPreference(key: String, value: String): Boolean = true.also { asked += "SET" }
        override fun liveHeartRate(seconds: Int, emit: (WearLinkProtocol.HeartRateSample) -> Unit): Int = 0.also { asked += "LIVE" }
    }

    private companion object {
        const val REGISTERED = "AA:BB:CC:DD:EE:01"
        const val BOND = "11:22:33:44:55:66"
        const val PHONE = "AA:BB:CC:DD:EE:99"
        const val NAME = "Galaxy Watch8"
        const val T0 = 1_760_000_000_000L
        const val BUFFER = 256 * 1024
        val FULL = setOf(
            WearLinkProtocol.CAP_HEART_RATE, WearLinkProtocol.CAP_SLEEP_MINUTES, WearLinkProtocol.CAP_STEPS,
            WearLinkProtocol.CAP_ASCENT, WearLinkProtocol.CAP_HRV, WearLinkProtocol.CAP_RESPIRATORY_RATE,
            WearLinkProtocol.CAP_SPO2, WearLinkProtocol.CAP_SKIN_TEMPERATURE, WearLinkProtocol.CAP_WORKOUTS,
            WearLinkProtocol.CAP_PREFERENCES, WearLinkProtocol.CAP_LIVE_HEART_RATE, WearLinkProtocol.CAP_PHONE_ACTIVE,
        )
    }
}
