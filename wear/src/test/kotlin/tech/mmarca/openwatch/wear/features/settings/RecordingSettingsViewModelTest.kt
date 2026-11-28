package tech.mmarca.openwatch.wear.features.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.wear.WatchPreferences
import tech.mmarca.openwatch.wear.WearSettings
import tech.mmarca.openwatch.wearlink.HeartRateInterval

/** The recording settings' ViewModel: a setting the phone changes shows at once, and every change goes to the port. */
class RecordingSettingsViewModelTest {

    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val port = FakePort()
    private val settings = MutableStateFlow(SETTINGS)

    @After
    fun tearDown() = scope.cancel()

    private fun viewModel(port: RecordingSettingsPort = this.port) = RecordingSettingsViewModel(port, settings, Dispatchers.Unconfined, scope).also { vm ->
        // The screen collects the state while it is shown; so does the test.
        scope.launch { vm.state.collect {} }
    }

    @Test
    fun `a setting the phone changes shows at once, with the Samsung settings where the watch has the platform`() {
        val vm = viewModel()
        assertEquals(HeartRateInterval.EVERY_10_SECONDS, vm.state.value.settings?.heartRateDay)
        assertTrue(vm.state.value.samsung)

        // What SET_PREF ends in: the stored settings change, and the flow follows.
        settings.value = SETTINGS.copy(heartRateDay = HeartRateInterval.POWER_SAVE)

        assertEquals(HeartRateInterval.POWER_SAVE, vm.state.value.settings?.heartRateDay)
    }

    @Test
    fun `the skin row shows on a Pixel with only the raw skin sensor`() {
        val vm = viewModel(FakePort(samsung = false, skin = true))

        assertTrue(vm.state.value.skin)
        assertFalse(vm.state.value.samsung)
    }

    @Test
    fun `the skin flag comes off the port, not off the Samsung flag`() {
        // The port answers the two apart; the screen shows each exactly as the port says.
        val vm = viewModel(FakePort(samsung = true, skin = false))

        assertTrue(vm.state.value.samsung)
        assertFalse(vm.state.value.skin)
    }

    @Test
    fun `a button changes the stored settings through the port, from what is stored then`() {
        val vm = viewModel()
        port.stored = SETTINGS.copy(maxHeartRate = 182)

        vm.setHeartRatePowerSave(true)
        vm.setSyncedSleepHold(false)
        vm.setHrvSource(WearSettings.Source.SAMSUNG_PLATFORM)

        assertEquals(
            SETTINGS.copy(
                heartRateDay = HeartRateInterval.POWER_SAVE,
                syncedSleepHold = false,
                hrvSource = WearSettings.Source.SAMSUNG_PLATFORM,
                maxHeartRate = 182,
            ),
            port.stored,
        )
    }

    @Test
    fun `auto-pause and workout detection are on until switched off`() {
        val vm = viewModel()
        assertTrue(SETTINGS.autoPause && SETTINGS.autoDetect)

        vm.setAutoPause(false)
        vm.setAutoDetect(false)

        assertEquals(SETTINGS.copy(autoPause = false, autoDetect = false), port.stored)
    }

    private class FakePort(
        private val samsung: Boolean = true,
        private val skin: Boolean = true,
    ) : RecordingSettingsPort {
        var stored = SETTINGS

        override fun samsung(): Boolean = samsung

        override fun skin(): Boolean = skin

        override fun change(change: (WatchPreferences) -> WatchPreferences) {
            stored = change(stored)
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
