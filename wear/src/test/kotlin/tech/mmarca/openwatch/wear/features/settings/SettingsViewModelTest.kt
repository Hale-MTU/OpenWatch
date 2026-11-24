package tech.mmarca.openwatch.wear.features.settings

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openwatch.wear.PendingPhone
import tech.mmarca.openwatch.wear.UnitSystem
import tech.mmarca.openwatch.wear.WatchPreferences
import tech.mmarca.openwatch.wear.WearLinkState
import tech.mmarca.openwatch.wear.WearSettings
import tech.mmarca.openwatch.wearlink.HeartRateInterval

/**
 * The settings screens' ViewModel: the grants are read off the main thread
 * when a screen starts and again when it comes back, the unit system follows
 * the settings, and every change goes to the port.
 */
class SettingsViewModelTest {

    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val port = FakePort()
    private val linkState = WearLinkState()
    private val settings = MutableStateFlow(SETTINGS)
    private var now = 1_000_000L

    @After
    fun tearDown() = scope.cancel()

    private fun viewModel(io: CoroutineDispatcher = Dispatchers.Unconfined) =
        SettingsViewModel(port, linkState, settings, io, clock = { now }, scope = scope).also { vm ->
            // The screen collects the state while it is shown; so does the test.
            scope.launch { vm.state.collect {} }
        }

    @Test
    fun `the grants are read on the IO dispatcher, not on the caller's thread`() {
        val io = Executors.newSingleThreadExecutor { Thread(it, "settings-io") }
        try {
            val vm = viewModel(io.asCoroutineDispatcher())

            val state = runBlocking { withTimeout(5_000) { vm.state.first { it.access != null } } }

            assertEquals(true, state.sensorAccessGranted)
            // Under the debug agent the thread name carries the coroutine after a space.
            assertEquals(listOf("settings-io"), port.accessThreads.map { it.substringBefore(" ") })
        } finally {
            io.shutdownNow()
        }
    }

    @Test
    fun `the grants are read again when the screen comes back`() {
        val vm = viewModel()
        assertEquals(true, vm.state.value.sensorAccessGranted)

        // Taken back in the system settings while the app was away.
        port.access = port.access.copy(heartRateInBackground = false)
        vm.refresh()

        assertEquals(false, vm.state.value.sensorAccessGranted)
        assertEquals(2, port.accessThreads.size)
    }

    @Test
    fun `the unit system follows the settings, and a change goes to the port`() {
        val vm = viewModel()
        assertEquals(UnitSystem.METRIC, vm.state.value.unitSystem)

        vm.setUnitSystem(UnitSystem.IMPERIAL)
        assertEquals(listOf(UnitSystem.IMPERIAL), port.unitSystems)

        // What the write ends in: the stored settings change, and the flow follows.
        settings.value = SETTINGS.copy(unitSystem = UnitSystem.IMPERIAL)
        assertEquals(UnitSystem.IMPERIAL, vm.state.value.unitSystem)
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
    fun `a request the wearer left unanswered goes when the screen comes back after it expired`() {
        val vm = viewModel()
        val phone = PendingPhone("AA:BB:CC:DD:EE:01", "token", "Pixel", sinceMillis = now)
        linkState.update { it.copy(pending = phone) }
        assertEquals(phone, vm.state.value.link.pending)

        now += WearLinkState.PENDING_TTL_MILLIS + 1
        vm.refresh()

        assertNull(vm.state.value.link.pending)
    }

    private class FakePort : SettingsPort {
        var access = SensorAccess(heartRate = true, heartRateInBackground = true, steps = null, nearbyDevices = true)
        val accessThreads = mutableListOf<String>()
        val trustCalls = mutableListOf<String>()
        val unitSystems = mutableListOf<UnitSystem>()

        override fun access(): SensorAccess {
            accessThreads += Thread.currentThread().name
            return access
        }

        override fun allow(phone: PendingPhone) {
            trustCalls += "allow ${phone.address}"
        }

        override fun block(phone: PendingPhone) {
            trustCalls += "block ${phone.address}"
        }

        override fun setUnitSystem(unitSystem: UnitSystem) {
            unitSystems += unitSystem
        }
    }

    private companion object {
        val SETTINGS = WatchPreferences(
            heartRateDay = HeartRateInterval.EVERY_10_SECONDS,
            heartRateSource = WearSettings.Source.RAW_SENSOR,
            hrvSource = WearSettings.Source.RAW_SENSOR,
            syncedSleepHold = true,
            skinTemperature = false,
            spo2AtNight = false,
        )
    }
}
