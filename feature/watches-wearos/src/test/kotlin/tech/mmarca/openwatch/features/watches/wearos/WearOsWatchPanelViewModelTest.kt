package tech.mmarca.openwatch.features.watches.wearos

import android.app.Application
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.mmarca.openwatch.core.presentation.ScreenError
import tech.mmarca.openwatch.core.security.testSeal
import tech.mmarca.openwatch.data.repository.testDeviceRepository
import tech.mmarca.openwatch.devices.FakeSharedPreferences
import tech.mmarca.openwatch.devices.core.DailyGoal
import tech.mmarca.openwatch.devices.core.SharedGoals
import tech.mmarca.openwatch.devices.wearos.WearOsAppStatus
import tech.mmarca.openwatch.devices.wearos.WearOsCompanionManager
import tech.mmarca.openwatch.devices.wearos.WearOsCompanionStatus
import tech.mmarca.openwatch.devices.wearos.WearOsLinkStore
import tech.mmarca.openwatch.devices.wearos.WearOsSkinTemperatureImport.NightMedian
import tech.mmarca.openwatch.devices.wearos.WearOsSyncCursorStore
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.DeviceIntegration
import tech.mmarca.openwatch.navigation.DeviceDetail
import tech.mmarca.openwatch.navigation.routeHandle
import tech.mmarca.openwatch.util.MainDispatcherRule
import tech.mmarca.openwatch.wearlink.WearLinkPreferences as Preferences

/** The Wear OS panel's status check on the way in, the live heart rate after it, and the skin temperature baseline's line. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WearOsWatchPanelViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo = testDeviceRepository()

    @Test
    fun `a missing Bluetooth permission on the Wear OS check is a grant affordance, checked once`() = runTest {
        val watch = repo.addDevice(
            displayName = "Galaxy Watch8",
            address = "7F:12:34:56:78:9A",
            bluetoothName = "Galaxy Watch8 (3A3B) LE",
            capabilities = emptySet(),
            kind = BleDeviceKind.WATCH,
            integration = DeviceIntegration.WEAROS,
        )
        val manager = mockk<WearOsCompanionManager>()
        coEvery { manager.checkWearOsWatchStatus(any(), any()) } throws SecurityException("BLUETOOTH_CONNECT")
        val vm = WearOsWatchPanelViewModel(
            savedStateHandle = routeHandle(DeviceDetail(watch.id)),
            deviceRepository = repo,
            companionManager = manager,
            linkStore = WearOsLinkStore(FakeSharedPreferences(), testSeal()),
            onboardWearOsWatch = mockk(relaxed = true),
            cursors = mockk(relaxed = true),
            sharedGoals = mockk(relaxed = true),
        )
        backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()

        assertEquals(ScreenError.PermissionDenied, vm.uiState.value.wearOsError)
        assertFalse(vm.uiState.value.isCheckingWearOsStatus)
        // A failed check leaves no timestamp; it must not retry on every emission.
        coVerify(exactly = 1) {
            manager.checkWearOsWatchStatus("7F:12:34:56:78:9A", "Galaxy Watch8 (3A3B) LE")
        }
    }

    @Test
    fun `the live heart rate starts once the first check and the settings read are done`() = runTest {
        val watch = repo.addDevice(
            displayName = "Galaxy Watch8",
            address = "7F:12:34:56:78:9A",
            bluetoothName = "Galaxy Watch8 (3A3B) LE",
            capabilities = emptySet(),
            kind = BleDeviceKind.WATCH,
            integration = DeviceIntegration.WEAROS,
        )
        val check = CompletableDeferred<WearOsCompanionStatus>()
        val manager = mockk<WearOsCompanionManager>()
        coEvery { manager.checkWearOsWatchStatus(any(), any()) } coAnswers { check.await() }
        coEvery { manager.watchSettings(any(), any()) } returns emptyMap()
        every { manager.liveHeartRate(any(), any()) } returns flowOf(72)
        val vm = WearOsWatchPanelViewModel(
            savedStateHandle = routeHandle(DeviceDetail(watch.id)),
            deviceRepository = repo,
            companionManager = manager,
            linkStore = WearOsLinkStore(FakeSharedPreferences(), testSeal()),
            onboardWearOsWatch = mockk(relaxed = true),
            cursors = mockk(relaxed = true),
            sharedGoals = mockk(relaxed = true),
        )
        backgroundScope.launch { vm.liveHeartRateBpm.collect { } }
        runCurrent()

        // The screen's first reads share the watch's one link; the status line does not wait for a live window.
        verify(exactly = 0) { manager.liveHeartRate(any(), any()) }
        check.complete(WearOsCompanionStatus(isPaired = true, appStatus = WearOsAppStatus.APP_RUNNING))
        runCurrent()

        coVerifyOrder {
            manager.checkWearOsWatchStatus("7F:12:34:56:78:9A", "Galaxy Watch8 (3A3B) LE")
            manager.watchSettings("7F:12:34:56:78:9A", "Galaxy Watch8 (3A3B) LE")
            manager.liveHeartRate("7F:12:34:56:78:9A", "Galaxy Watch8 (3A3B) LE")
        }
        assertEquals(72, vm.liveHeartRateBpm.value)
    }

    @Test
    fun `the nights the skin temperature baseline still needs are read from what the sync keeps, each time the screen asks`() = runTest {
        val watch = repo.addDevice(
            displayName = "Galaxy Watch8",
            address = "7F:12:34:56:78:9A",
            bluetoothName = "Galaxy Watch8 (3A3B) LE",
            capabilities = emptySet(),
            kind = BleDeviceKind.WATCH,
            integration = DeviceIntegration.WEAROS,
        )
        // The watch is out of reach: its settings stay unread, and the count needs neither it nor a sync.
        val manager = mockk<WearOsCompanionManager>()
        coEvery { manager.checkWearOsWatchStatus(any(), any()) } returns WearOsCompanionStatus()
        coEvery { manager.watchSettings(any(), any()) } returns null
        val cursors = mockk<WearOsSyncCursorStore>()
        val nights = List(3) { NightMedian(LocalDate.parse("2026-11-07").plusDays(it.toLong()), 34.0, minutes = 400) }
        every { cursors.skinNightMedians(watch.id) } returnsMany listOf(emptyList(), nights.take(1), nights.take(2), nights)
        val vm = WearOsWatchPanelViewModel(
            savedStateHandle = routeHandle(DeviceDetail(watch.id)),
            deviceRepository = repo,
            companionManager = manager,
            linkStore = WearOsLinkStore(FakeSharedPreferences(), testSeal()),
            onboardWearOsWatch = mockk(relaxed = true),
            cursors = cursors,
            sharedGoals = mockk(relaxed = true),
        )
        runCurrent()

        // No night was ever remembered: nothing to say, this phone or watch may not measure it at all.
        vm.refreshSkinBaseline()
        assertNull(vm.uiState.value.skinNightsToGo)

        // After the first night's sync, two more before a night is written; after the second, one.
        vm.refreshSkinBaseline()
        assertEquals(2, vm.uiState.value.skinNightsToGo)
        vm.refreshSkinBaseline()
        assertEquals(1, vm.uiState.value.skinNightsToGo)

        // The baseline exists: the line goes.
        vm.refreshSkinBaseline()
        assertNull(vm.uiState.value.skinNightsToGo)
    }

    @Test
    fun `the skin temperature line is kept back when the watch says the measurement is off`() {
        val forming = WearOsWatchPanelUiState(skinNightsToBaseline = 2)

        // The settings were not read: the watch may be out of reach, the nights so far still count.
        assertEquals(2, forming.skinNightsToGo)
        assertEquals(2, forming.copy(watchSettings = mapOf(Preferences.SKIN_TEMPERATURE to Preferences.ON)).skinNightsToGo)
        assertNull(forming.copy(watchSettings = mapOf(Preferences.SKIN_TEMPERATURE to Preferences.OFF)).skinNightsToGo)
        // A watch that answers without the setting does not measure it.
        assertNull(forming.copy(watchSettings = emptyMap()).skinNightsToGo)
    }

    @Test
    fun `a goal the watch took is shared with the connected apps, one it refused is not`() = runTest {
        val watch = repo.addDevice(
            displayName = "Galaxy Watch8",
            address = "7F:12:34:56:78:9A",
            bluetoothName = "Galaxy Watch8 (3A3B) LE",
            capabilities = emptySet(),
            kind = BleDeviceKind.WATCH,
            integration = DeviceIntegration.WEAROS,
        )
        val manager = mockk<WearOsCompanionManager>(relaxed = true)
        coEvery { manager.setWatchSetting(any(), any(), any(), any()) } returns true
        coEvery { manager.watchSettings(any(), any()) } returnsMany listOf(
            mapOf(Preferences.GOAL_STEPS to "12000"),
            mapOf(Preferences.GOAL_STEPS to "12000"),
        )
        val goals = mockk<SharedGoals>(relaxed = true)
        val vm = WearOsWatchPanelViewModel(
            savedStateHandle = routeHandle(DeviceDetail(watch.id)),
            deviceRepository = repo,
            companionManager = manager,
            linkStore = WearOsLinkStore(FakeSharedPreferences(), testSeal()),
            onboardWearOsWatch = mockk(relaxed = true),
            cursors = mockk(relaxed = true),
            sharedGoals = goals,
        )
        runCurrent()

        vm.setWatchSetting(Preferences.GOAL_STEPS, "12000")
        runCurrent()
        // The watch still holds 12000: a 9000 it refused is not shared.
        vm.setWatchSetting(Preferences.GOAL_STEPS, "9000")
        runCurrent()

        verify(exactly = 1) { goals.set(DailyGoal.STEPS, 12_000) }
        verify(exactly = 0) { goals.set(DailyGoal.STEPS, 9_000) }
    }
}
