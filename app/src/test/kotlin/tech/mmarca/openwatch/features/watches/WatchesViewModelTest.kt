package tech.mmarca.openwatch.features.watches

import tech.mmarca.openwatch.devices.core.pairing.CompanionFilter
import android.content.Context
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import tech.mmarca.openwatch.core.permissions.OsPermissionsService
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.devices.FakeSharedPreferences
import tech.mmarca.openwatch.devices.core.DeviceClassification
import tech.mmarca.openwatch.devices.core.pairing.WatchPairingPort
import tech.mmarca.openwatch.devices.wearos.OnboardWearOsWatchUseCase
import tech.mmarca.openwatch.domain.model.OsPermissionCatalog
import tech.mmarca.openwatch.domain.model.OsPermissionId
import tech.mmarca.openwatch.domain.model.OsPermissionRow
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleDiscoveredDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration
import tech.mmarca.openwatch.sensors.ble.BleSensorCoordinator
import tech.mmarca.openwatch.util.MainDispatcherRule

/**
 * The sheet-level flow: the permission checklist, what selecting and onboarding a watch
 * leave on screen, and the no-companion notice's lifetime. Registry writes are
 * `OnboardWearOsWatchUseCaseTest`.
 */
class WatchesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Records what onboarding asked the platform to do; answers as set up. */
    private class FakePairing : WatchPairingPort {
        var associateResult = true
        val calls = mutableListOf<String>()

        override suspend fun associateCompanion(
            address: String,
            displayName: String?,
            filter: CompanionFilter,
        ): Boolean {
            calls.add("associate:$address")
            return associateResult
        }

        override suspend fun disassociateCompanion(address: String) {
            calls.add("disassociate:$address")
        }
    }

    private lateinit var repo: BleDeviceRepository
    private lateinit var pairing: FakePairing
    private lateinit var coordinator: BleSensorCoordinator

    /** How the coordinator classifies whatever the test selects. */
    private var classification = DeviceClassification(
        integration = DeviceIntegration.WEAROS,
        kind = BleDeviceKind.WATCH,
    )

    private val watch = BleDiscoveredDevice(
        address = "A8:D1:62:BE:3A:3B",
        name = "Galaxy Watch8",
        rssi = -55,
        suggestedCapabilities = emptySet(),
    )

    @Before
    fun setUp() {
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns FakeSharedPreferences()
        repo = BleDeviceRepository(context)
        pairing = FakePairing()
        coordinator = mockk<BleSensorCoordinator>().also { fake ->
            every { fake.discoveredDevices } returns MutableStateFlow(emptyList())
            every { fake.startScan(any()) } just runs
            every { fake.stopScan() } just runs
            every { fake.classifyDiscoveredDevice(any()) } answers { classification }
        }
    }

    /** Granted by default, so pairing tests are not diverted into the checklist. */
    private fun osPermissions(
        catalog: OsPermissionCatalog = OsPermissionCatalog(
            rows = listOf(
                OsPermissionRow(OsPermissionId.BLUETOOTH, listOf("scan", "connect"), granted = true),
            ),
        ),
    ): OsPermissionsService = mockk<OsPermissionsService>(relaxed = true).also { service ->
        every { service.watchSetupCatalog() } returns catalog
    }

    private fun viewModel(
        os: OsPermissionsService = osPermissions(),
    ) = WatchesViewModel(
        deviceRepository = repo,
        sensorCoordinator = coordinator,
        onboardWearOsWatch = OnboardWearOsWatchUseCase(pairing, repo),
        osPermissionsService = os,
    )

    // Android's own permissions, asked when the first watch is added.

    private fun osRow(id: OsPermissionId, granted: Boolean, vararg permissions: String) =
        OsPermissionRow(id = id, permissions = permissions.toList(), granted = granted)

    @Test
    fun `adding a watch with everything granted goes straight to the scan`() = runTest {
        val vm = viewModel()

        vm.startAdd()

        assertTrue(vm.uiState.value.showAddFlow)
        assertFalse(vm.uiState.value.showPermissionsGate)
    }

    @Test
    fun `an outstanding permission shows the checklist instead of scanning`() = runTest {
        val vm = viewModel(
            os = osPermissions(
                OsPermissionCatalog(
                    rows = listOf(
                        osRow(OsPermissionId.BLUETOOTH, granted = false, "scan", "connect"),
                        osRow(OsPermissionId.LOCATION, granted = true, "location"),
                        osRow(OsPermissionId.BATTERY_OPTIMIZATION, granted = false),
                    ),
                ),
            ),
        )

        vm.startAdd()

        // Scanning without Bluetooth finds nothing, so the ask comes first.
        assertTrue(vm.uiState.value.showPermissionsGate)
        assertFalse(vm.uiState.value.showAddFlow)
        // One dialog for what the runtime can ask; the granted row adds nothing.
        assertEquals(listOf("scan", "connect"), vm.uiState.value.osPermissions.requestablePermissions)
        // Settings screens cannot be batched, so they queue instead.
        assertEquals(
            listOf(OsPermissionId.BATTERY_OPTIMIZATION),
            vm.uiState.value.osPermissions.outstandingSpecials,
        )
    }

    @Test
    fun `continuing anyway leaves the checklist for the scan`() = runTest {
        val vm = viewModel(
            os = osPermissions(
                OsPermissionCatalog(rows = listOf(osRow(OsPermissionId.BATTERY_OPTIMIZATION, false))),
            ),
        )
        vm.startAdd()

        vm.openAddFlow()

        // Only Bluetooth blocks pairing; a refused extra must not trap the user.
        assertFalse(vm.uiState.value.showPermissionsGate)
        assertTrue(vm.uiState.value.showAddFlow)
    }

    @Test
    fun `the queue opens one settings screen per call`() = runTest {
        val service = osPermissions(
            OsPermissionCatalog(
                rows = listOf(
                    osRow(OsPermissionId.BATTERY_OPTIMIZATION, granted = false),
                    osRow(OsPermissionId.LOCATION, granted = false),
                ),
            ),
        )
        every { service.openSettingsFor(any()) } returns true
        val vm = viewModel(os = service)
        vm.startAdd()

        vm.queueAllSpecialPermissions()
        vm.openNextSpecialPermission()

        // Only the first: the app leaves the foreground to show it, so a second would never be seen.
        verify(exactly = 1) { service.openSettingsFor(OsPermissionId.BATTERY_OPTIMIZATION) }
        verify(exactly = 0) { service.openSettingsFor(OsPermissionId.LOCATION) }

        // Coming back from that screen hands over the next one.
        vm.openNextSpecialPermission()
        verify(exactly = 1) { service.openSettingsFor(OsPermissionId.LOCATION) }
        assertTrue(vm.uiState.value.pendingSpecialPermissions.isEmpty())
    }

    @Test
    fun `a queued permission granted elsewhere is dropped, not reopened`() = runTest {
        val service = osPermissions(
            OsPermissionCatalog(
                rows = listOf(
                    osRow(OsPermissionId.BATTERY_OPTIMIZATION, granted = false),
                    osRow(OsPermissionId.LOCATION, granted = false),
                ),
            ),
        )
        every { service.openSettingsFor(any()) } returns true
        val vm = viewModel(os = service)
        vm.startAdd()
        vm.queueAllSpecialPermissions()

        // The user grants the battery one before the queue reaches it.
        every { service.watchSetupCatalog() } returns OsPermissionCatalog(
            rows = listOf(
                osRow(OsPermissionId.BATTERY_OPTIMIZATION, granted = true),
                osRow(OsPermissionId.LOCATION, granted = false),
            ),
        )
        vm.openNextSpecialPermission()

        // A settled screen is skipped.
        verify(exactly = 0) { service.openSettingsFor(OsPermissionId.BATTERY_OPTIMIZATION) }
        verify(exactly = 1) { service.openSettingsFor(OsPermissionId.LOCATION) }
    }

    @Test
    fun `an empty queue opens nothing`() = runTest {
        val service = osPermissions(
            OsPermissionCatalog(rows = listOf(osRow(OsPermissionId.BATTERY_OPTIMIZATION, false))),
        )
        val vm = viewModel(os = service)

        // Every resume calls this; outside a walk there is nothing to open.
        vm.openNextSpecialPermission()

        verify(exactly = 0) { service.openSettingsFor(any()) }
    }

    @Test
    fun `selecting a watch stops the scan and names it`() = runTest {
        val vm = viewModel()

        vm.selectDiscoveredDevice(watch)

        assertEquals(DeviceIntegration.WEAROS, vm.uiState.value.addingIntegration)
        assertEquals(BleDeviceKind.WATCH, vm.uiState.value.selectedClassification?.kind)
        assertEquals("Galaxy Watch8", vm.uiState.value.addDisplayName)
        verify { coordinator.stopScan() }
    }

    @Test
    fun `onboarding registers the watch and closes the sheet`() = runTest {
        val vm = viewModel()
        vm.openAddFlow()
        vm.selectDiscoveredDevice(watch)

        var closed: Boolean? = null
        vm.onboardSelectedWatch { closed = it }

        assertEquals(true, closed)
        val registered = repo.devices.single()
        assertTrue(registered.isWearosWatch)
        assertFalse(vm.uiState.value.isOnboarding)
        assertFalse(vm.uiState.value.showAddFlow)
        assertEquals(listOf("associate:A8:D1:62:BE:3A:3B"), pairing.calls)
    }

    @Test
    fun `a device no integration claims is not onboarded`() = runTest {
        classification = DeviceClassification.SENSOR
        val vm = viewModel()
        vm.openAddFlow()
        vm.selectDiscoveredDevice(watch)

        var closed: Boolean? = null
        vm.onboardSelectedWatch { closed = it }

        assertEquals(false, closed)
        assertTrue(repo.devices.isEmpty())
        assertTrue(pairing.calls.isEmpty())
    }

    @Test
    fun `a declined companion association is recorded, not failed`() = runTest {
        pairing.associateResult = false
        val vm = viewModel()
        vm.openAddFlow()
        vm.selectDiscoveredDevice(watch)

        var closed: Boolean? = null
        vm.onboardSelectedWatch { closed = it }

        assertEquals(true, closed)
        assertEquals(1, repo.devices.size)
        assertEquals(WatchOnboardNotice.NO_COMPANION, vm.uiState.value.onboardNotice)
    }

    @Test
    fun `the no-companion flag survives the sheet closing, then clears`() = runTest {
        pairing.associateResult = false
        val vm = viewModel()
        vm.openAddFlow()
        vm.selectDiscoveredDevice(watch)

        vm.onboardSelectedWatch()

        // The screen reads the notice after the sheet pops, so closing must not consume it.
        assertFalse(vm.uiState.value.showAddFlow)
        assertEquals(WatchOnboardNotice.NO_COMPANION, vm.uiState.value.onboardNotice)

        // Starting a fresh add is what clears it, so the notice fires once.
        vm.openAddFlow()
        assertNull(vm.uiState.value.onboardNotice)
    }

    @Test
    fun `a blank name falls back to the advertised one`() = runTest {
        val vm = viewModel()
        vm.openAddFlow()
        vm.selectDiscoveredDevice(watch)
        vm.updateAddDisplayName("   ")

        vm.onboardSelectedWatch()

        assertEquals("Galaxy Watch8", repo.devices.single().displayName)
    }
}
