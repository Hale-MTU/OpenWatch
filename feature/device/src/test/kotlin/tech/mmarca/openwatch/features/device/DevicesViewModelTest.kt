package tech.mmarca.openwatch.features.device

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tech.mmarca.openwatch.core.permissions.OsPermissionsService
import tech.mmarca.openwatch.core.presentation.ScreenError
import tech.mmarca.openwatch.data.repository.testDeviceRepository
import tech.mmarca.openwatch.devices.core.DeviceClassification
import tech.mmarca.openwatch.devices.core.DeviceFamilies
import tech.mmarca.openwatch.devices.core.DeviceFamily
import tech.mmarca.openwatch.devices.core.DeviceFeature
import tech.mmarca.openwatch.devices.core.DevicePairing
import tech.mmarca.openwatch.devices.core.DevicePairingResult
import tech.mmarca.openwatch.devices.core.FakeDeviceFamily
import tech.mmarca.openwatch.devices.core.PairingFailure
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleDiscoveredDevice
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration
import tech.mmarca.openwatch.domain.model.OsPermissionCatalog
import tech.mmarca.openwatch.domain.model.OsPermissionId
import tech.mmarca.openwatch.domain.model.OsPermissionRow
import tech.mmarca.openwatch.sensors.ble.BleSensorCoordinator
import tech.mmarca.openwatch.util.MainDispatcherRule

/**
 * The add-a-device flow for a family that pairs through the app: it names no family, so these
 * run it over a made-up one. What the scan listens for, what is offered and how it pairs are the
 * family's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val registry = testDeviceRepository()
    private val discovered = MutableStateFlow<List<BleDiscoveredDevice>>(emptyList())
    private var watchClaims = setOf<String>()
    private val coordinator = mockk<BleSensorCoordinator>().also { fake ->
        every { fake.discoveredDevices } returns discovered
        every { fake.startScan(any(), any()) } just runs
        every { fake.stopScan() } just runs
        every { fake.classifyDiscoveredDevice(any()) } answers {
            if (firstArg<BleDiscoveredDevice>().address in watchClaims) {
                DeviceClassification(DeviceIntegration.GARMIN, BleDeviceKind.WATCH)
            } else {
                DeviceClassification.SENSOR
            }
        }
    }
    private var bluetoothGranted = true
    private val os = mockk<OsPermissionsService>(relaxed = true).also { service ->
        every { service.deviceSetupCatalog() } answers {
            OsPermissionCatalog(listOf(OsPermissionRow(OsPermissionId.BLUETOOTH, listOf("scan", "connect"), bluetoothGranted)))
        }
    }

    /** A family that pairs devices advertising its one service, and fails as told. */
    private inner class PairingFamily : DeviceFamily, DevicePairing {
        var failure: PairingFailure? = null
        override val id = FAMILY
        override val pairing: DevicePairing = this
        override val scanServiceUuids = setOf(SERVICE)
        override fun owns(device: BleSensorDevice) = device.integration?.storageName == FAMILY
        override fun features(device: BleSensorDevice) = emptySet<DeviceFeature>()
        override suspend fun forget(device: BleSensorDevice, deleteHistory: Boolean) = Unit
        override fun claim(device: BleDiscoveredDevice): BleDeviceKind? = METER.takeIf { SERVICE in device.advertisedServiceUuids }
        override suspend fun pair(device: BleDiscoveredDevice, displayName: String): DevicePairingResult {
            failure?.let { return DevicePairingResult.Failed(it) }
            val row = registry.addDevice(displayName, device.address, device.name, emptySet(), kind = METER, integration = DeviceIntegration(FAMILY))
            return DevicePairingResult.Paired(row.id)
        }
    }

    private val family = PairingFamily()
    private val watchFamily = FakeDeviceFamily("GARMIN", emptySet()) { it.integration == DeviceIntegration.GARMIN }

    private fun viewModel(vararg families: DeviceFamily = arrayOf(family, watchFamily)) =
        DevicesViewModel(registry, DeviceFamilies(families.associateBy { it.id }), coordinator, os)

    private fun scanned(address: String, vararg services: String) =
        BleDiscoveredDevice(address, "Meter", rssi = -60, suggestedCapabilities = emptySet(), advertisedServiceUuids = services.toSet())

    @Test
    fun `nothing shows while no family pairs through the app`() = runTest {
        assertFalse(viewModel(watchFamily).uiState.value.available)
        assertTrue(viewModel().uiState.value.available)
    }

    @Test
    fun `the scan listens for what the families name, and offers what one claims and no watch family does`() = runTest {
        val model = viewModel()
        model.startAdd()

        verify { coordinator.startScan(false, listOf(UUID.fromString(SERVICE))) }
        watchClaims = setOf("AA:00:00:00:00:02")
        discovered.value = listOf(
            scanned("AA:00:00:00:00:01", SERVICE),
            scanned("AA:00:00:00:00:02", SERVICE),
            scanned("AA:00:00:00:00:03", "0000180d-0000-1000-8000-00805f9b34fb"),
        )

        val offered = model.uiState.value.candidates
        assertEquals(listOf("AA:00:00:00:00:01"), offered.map { it.device.address })
        assertEquals(FAMILY, offered.single().familyId)
        assertEquals(METER, offered.single().kind)
    }

    @Test
    fun `the Bluetooth grants come first when they are missing`() = runTest {
        bluetoothGranted = false
        val model = viewModel()

        model.startAdd()

        assertTrue(model.uiState.value.showPermissionsGate)
        assertFalse(model.uiState.value.showAddFlow)
        verify(exactly = 0) { coordinator.startScan(any(), any()) }
    }

    @Test
    fun `pairing goes through the family, closes the dialog and lists the device`() = runTest {
        val model = viewModel()
        model.startAdd()
        discovered.value = listOf(scanned("AA:00:00:00:00:01", SERVICE))
        model.select(model.uiState.value.candidates.single())
        model.updateName("Kitchen meter")

        model.pair()

        val state = model.uiState.value
        assertFalse(state.showAddFlow)
        assertEquals(listOf("Kitchen meter"), state.devices.map { it.displayName })
        // Only the families that pair through the app: a watch is listed in its own section.
        registry.addDevice("Watch", "AA:00:00:00:00:09", null, emptySet(), kind = BleDeviceKind.WATCH, integration = DeviceIntegration.GARMIN)
        assertEquals(1, model.uiState.value.devices.size)
    }

    @Test
    fun `a pairing that fails says why and keeps the dialog open`() = runTest {
        family.failure = PairingFailure.NOT_ASSOCIATED
        val model = viewModel()
        model.startAdd()
        discovered.value = listOf(scanned("AA:00:00:00:00:01", SERVICE))
        model.select(model.uiState.value.candidates.single())

        model.pair()

        val state = model.uiState.value
        assertTrue(state.showAddFlow)
        assertEquals(ScreenError.Text(R.string.devices_pair_not_associated), state.error)
        assertTrue(registry.devices.isEmpty())
        assertNull(state.devices.firstOrNull())
    }

    private companion object {
        const val FAMILY = "SOMEVENDOR"
        const val SERVICE = "00001808-0000-1000-8000-00805f9b34fb"
        val METER = BleDeviceKind("GLUCOSE_METER")
    }
}
