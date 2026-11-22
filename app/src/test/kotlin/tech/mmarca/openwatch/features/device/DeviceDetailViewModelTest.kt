package tech.mmarca.openwatch.features.device

import android.app.Application
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.mmarca.openwatch.data.repository.testDeviceRepository
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.DeviceIntegration
import tech.mmarca.openwatch.navigation.DeviceDetail
import tech.mmarca.openwatch.navigation.routeHandle
import tech.mmarca.openwatch.util.MainDispatcherRule

/** One route for every paired device: the scale gets its own screen, everything else the device screen. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeviceDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val registry = testDeviceRepository()
    private val scale = registry.addDevice(
        displayName = "Bathroom",
        address = "8C:D0:B2:F6:BE:EF",
        bluetoothName = "MIBFS",
        capabilities = emptySet(),
        kind = BleDeviceKind.SCALE,
        integration = DeviceIntegration.XIAOMI,
    )
    private val watch = registry.addDevice(
        displayName = "vívoactive 5",
        address = "E0:48:24:D5:F7:10",
        bluetoothName = "vívoactive 5",
        capabilities = emptySet(),
        kind = BleDeviceKind.WATCH,
        integration = DeviceIntegration.GARMIN,
    )

    private fun screenFor(deviceId: String) =
        DeviceDetailViewModel(routeHandle(DeviceDetail(deviceId)), registry).screen.value

    @Test
    fun `the scale opens its own screen, a watch and an unknown id the device screen`() = runTest {
        assertEquals(DeviceDetailScreen.SCALE, screenFor(scale.id))
        assertEquals(DeviceDetailScreen.DEVICE, screenFor(watch.id))
        // The device screen says the device is gone.
        assertEquals(DeviceDetailScreen.DEVICE, screenFor("removed-meanwhile"))
    }

    @Test
    fun `removing the scale does not swap its screen while it goes back`() = runTest {
        val model = DeviceDetailViewModel(routeHandle(DeviceDetail(scale.id)), registry)
        assertEquals(DeviceDetailScreen.SCALE, model.screen.value)

        registry.removeDevice(scale.id)

        assertEquals(DeviceDetailScreen.SCALE, model.screen.value)
    }
}
