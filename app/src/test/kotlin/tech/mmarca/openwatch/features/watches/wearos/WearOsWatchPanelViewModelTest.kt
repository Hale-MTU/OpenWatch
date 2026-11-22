package tech.mmarca.openwatch.features.watches.wearos

import android.app.Application
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.mmarca.openwatch.core.presentation.ScreenError
import tech.mmarca.openwatch.core.security.testSeal
import tech.mmarca.openwatch.data.repository.testDeviceRepository
import tech.mmarca.openwatch.devices.FakeSharedPreferences
import tech.mmarca.openwatch.devices.wearos.WearOsCompanionManager
import tech.mmarca.openwatch.devices.wearos.WearOsLinkStore
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.DeviceIntegration
import tech.mmarca.openwatch.navigation.DeviceDetail
import tech.mmarca.openwatch.navigation.routeHandle
import tech.mmarca.openwatch.util.MainDispatcherRule

/** The Wear OS panel's status check on the way in. */
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
}
