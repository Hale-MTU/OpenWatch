package tech.mmarca.openwatch.wear.features.status

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.wear.PendingPhone
import tech.mmarca.openwatch.wear.WearLinkState

/**
 * The status screen's ViewModel: the store is read off the main thread, at
 * each tick and on a refresh, and every change goes to the port.
 */
class StatusViewModelTest {

    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val port = FakePort()
    private val linkState = WearLinkState()
    private val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private var now = 1_000_000L

    @After
    fun tearDown() = scope.cancel()

    private fun viewModel(io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined) =
        StatusViewModel(port, linkState, ticks, io, clock = { now }, scope = scope).also { vm ->
            // The screen collects the state while it is shown; so does the test.
            scope.launch { vm.state.collect {} }
        }

    @Test
    fun `the store is read on the IO dispatcher, not on the caller's thread`() {
        val io = Executors.newSingleThreadExecutor { Thread(it, "status-io") }
        try {
            val vm = viewModel(io.asCoroutineDispatcher())
            ticks.tryEmit(Unit)

            val state = runBlocking { withTimeout(5_000) { vm.state.first { it.readings != null } } }

            assertEquals(64, state.readings?.latestBpm)
            // Under the debug agent the thread name carries the coroutine after a space.
            assertEquals(listOf("status-io"), port.readThreads.map { it.substringBefore(" ") })
        } finally {
            io.shutdownNow()
        }
    }

    @Test
    fun `nothing is read before the first tick, then once a tick and once a refresh`() {
        val vm = viewModel()
        assertNull(vm.state.value.readings)
        assertEquals(0, port.reads)

        ticks.tryEmit(Unit)
        ticks.tryEmit(Unit)
        vm.refresh()

        assertEquals(3, port.reads)
        assertEquals(4_210L, vm.state.value.readings?.heartRateSamples)
    }

    @Test
    fun `a read that fails keeps what the screen showed`() {
        val vm = viewModel()
        ticks.tryEmit(Unit)
        port.failing = true
        port.bpm = 99

        ticks.tryEmit(Unit)

        assertEquals(64, vm.state.value.readings?.latestBpm)
    }

    @Test
    fun `a request the wearer left unanswered goes at the next tick after it expired`() {
        val vm = viewModel()
        val phone = PendingPhone("AA:BB:CC:DD:EE:01", "token", "Pixel", sinceMillis = now)
        linkState.update { it.copy(pending = phone) }
        assertEquals(phone, vm.state.value.link.pending)

        now += WearLinkState.PENDING_TTL_MILLIS + 1
        ticks.tryEmit(Unit)

        assertNull(vm.state.value.link.pending)
    }

    @Test
    fun `allow and block go to the trust store through the port`() {
        val vm = viewModel()
        val phone = PendingPhone("AA:BB:CC:DD:EE:02", "token", "Pixel", sinceMillis = now)

        vm.allow(phone)
        vm.block(phone)

        assertEquals(listOf("allow AA:BB:CC:DD:EE:02", "block AA:BB:CC:DD:EE:02"), port.trustCalls)
    }

    @Test
    fun `a grant that is missing offers the grant button`() {
        val vm = viewModel()
        port.stepsGranted = false
        ticks.tryEmit(Unit)

        assertTrue(vm.state.value.needsGrant)
    }

    private class FakePort : StatusPort {
        var reads = 0
        val readThreads = mutableListOf<String>()
        var failing = false
        var bpm = 64
        var stepsGranted = true
        val trustCalls = mutableListOf<String>()

        override fun read(): StatusReadings? {
            reads++
            readThreads += Thread.currentThread().name
            if (failing) return null
            return StatusReadings(
                heartRateGranted = true,
                bluetoothGranted = true,
                stepsGranted = stepsGranted,
                hasStepCounter = true,
                latestBpm = bpm,
                heartRateSamples = 4_210,
                sleepMinutes = 1_380,
                stepMinutes = 610,
                samsungDiagnostics = null,
                samsungPlatform = false,
                ppgLogAvailable = false,
            )
        }


        override fun allow(phone: PendingPhone) {
            trustCalls += "allow ${phone.address}"
        }

        override fun block(phone: PendingPhone) {
            trustCalls += "block ${phone.address}"
        }

        override fun togglePpgLog() = Unit
    }
}
