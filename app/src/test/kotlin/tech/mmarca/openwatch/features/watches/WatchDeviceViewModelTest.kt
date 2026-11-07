package tech.mmarca.openwatch.features.watches

import tech.mmarca.openwatch.devices.core.pairing.CompanionFilter
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.devices.FakeSharedPreferences
import tech.mmarca.openwatch.devices.core.sync.AutoSyncInterval
import tech.mmarca.openwatch.devices.core.pairing.WatchPairingPort
import tech.mmarca.openwatch.devices.wearos.OnboardWearOsWatchUseCase
import tech.mmarca.openwatch.devices.wearos.WearOsCompanionManager
import tech.mmarca.openwatch.core.presentation.ScreenError
import tech.mmarca.openwatch.devices.wearos.WearOsLinkStore
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorCapability
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration
import tech.mmarca.openwatch.navigation.WATCH_DEVICE_ID_ARG
import tech.mmarca.openwatch.util.MainDispatcherRule

/** Renaming a watch, the sync settings, and what forgetting a device does and does not do at the OS level. */
class WatchDeviceViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakePairing : WatchPairingPort {
        val calls = mutableListOf<String>()


        override suspend fun associateCompanion(
            address: String,
            displayName: String?,
            filter: CompanionFilter,
        ) = true

        override suspend fun disassociateCompanion(address: String) {
            synchronized(calls) { calls.add("disassociate:$address") }
        }

        fun snapshot(): List<String> = synchronized(calls) { calls.toList() }
    }

    private lateinit var repo: BleDeviceRepository
    private lateinit var pairing: FakePairing
    private val healthConnectManager = mockk<tech.mmarca.openwatch.healthconnect.HealthConnectGateway>(relaxed = true)
    private val wearOsLinkStore = WearOsLinkStore(FakeSharedPreferences())

    /** The scheduler owns the stored interval and the WorkManager side, so it is one stub that remembers. */
    private lateinit var autoSyncScheduler: WatchAutoSyncScheduler
    private val autoSyncIntervals = mutableMapOf<String, AutoSyncInterval>()

    @Before
    fun setUp() {
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns FakeSharedPreferences()
        repo = BleDeviceRepository(context)
        pairing = FakePairing()
        autoSyncIntervals.clear()
        autoSyncScheduler = mockk(relaxed = true)
        every { autoSyncScheduler.interval(any()) } answers {
            autoSyncIntervals[firstArg()] ?: AutoSyncInterval.OFF
        }
        every { autoSyncScheduler.setInterval(any(), any()) } answers {
            autoSyncIntervals[firstArg()] = secondArg()
        }
    }

    private fun addWatch(): BleSensorDevice = repo.addDevice(
        displayName = "Galaxy Watch8",
        address = "4A:F1:89:49:3C:85",
        bluetoothName = "Galaxy Watch8 (89FZ)",
        capabilities = emptySet(),
        kind = BleDeviceKind.WATCH,
        integration = DeviceIntegration.WEAROS,
    )

    private fun viewModel(
        deviceId: String,
        wearOsCompanionManager: WearOsCompanionManager = mockk(relaxed = true),
    ) = WatchDeviceViewModel(
        savedStateHandle = SavedStateHandle(mapOf(WATCH_DEVICE_ID_ARG to deviceId)),
        deviceRepository = repo,
        syncController = mockk<DeviceSyncController>().also {
            every { it.state } returns MutableStateFlow(DeviceSyncUiState())
        },
        autoSyncScheduler = autoSyncScheduler,
        onboardWearOsWatch = OnboardWearOsWatchUseCase(pairing, repo),
        healthConnectManager = healthConnectManager,
        // The cleanup after a removal runs on the default dispatcher. Here it runs at once.
        dispatchers = mainDispatcherRule.dispatcherProvider,
        wearOsCompanionManager = wearOsCompanionManager,
        wearOsLinkStore = wearOsLinkStore,
    )

    @Test
    fun `renaming trims the new name and persists it`() = runTest {
        val watch = addWatch()
        val vm = viewModel(watch.id)

        // Trimmed, because a name padded with spaces is a typo, not a choice.
        vm.rename("  Wrist watch  ")

        assertEquals("Wrist watch", repo.devices.single().displayName)
        assertEquals("Wrist watch", vm.uiState.value.device?.displayName)
    }

    @Test
    fun `a watch can be renamed even though it has no capabilities`() = runTest {
        val watch = addWatch()
        assertTrue(watch.capabilities.isEmpty())
        val vm = viewModel(watch.id)

        vm.rename("Running watch")

        // The sensor rule ("select at least one capability") must not fire here.
        assertEquals("Running watch", repo.devices.single().displayName)
        assertTrue(repo.devices.single().capabilities.isEmpty())
    }

    @Test
    fun `a blank rename is refused rather than blanking the row`() = runTest {
        val watch = addWatch()
        val vm = viewModel(watch.id)

        vm.rename("   ")

        assertEquals("Galaxy Watch8", repo.devices.single().displayName)
    }

    @Test
    fun `the screen learns when Health Connect's background access is missing`() = runTest {
        coEvery { healthConnectManager.isBackgroundReadGrantMissing() } returns true
        val watch = addWatch()
        val vm = viewModel(watch.id)
        backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()
        assertFalse(vm.uiState.value.backgroundReadMissing)

        vm.refreshBackgroundAccess()
        runCurrent()
        assertTrue(vm.uiState.value.backgroundReadMissing)

        // After the permission screen returns with the grant, the notice goes away.
        coEvery { healthConnectManager.isBackgroundReadGrantMissing() } returns false
        vm.refreshBackgroundAccess()
        runCurrent()
        assertFalse(vm.uiState.value.backgroundReadMissing)
    }

    @Test
    fun `forgetting a Wear OS watch drops its association and link token, so adding it again asks on the watch`() = runTest {
        val watch = addWatch()
        val token = wearOsLinkStore.tokenFor(watch.address, "A8:D1:62:BE:3A:3B")
        val vm = viewModel(watch.id)
        backgroundScope.launch { vm.uiState.collect { } }

        vm.removeDevice()
        runCurrent()

        assertTrue(repo.devices.isEmpty())
        assertNotEquals(token, wearOsLinkStore.tokenFor(watch.address, "A8:D1:62:BE:3A:3B"))
        // Only the association: the phone's own Wear OS pairing is not this app's to undo.
        assertEquals(listOf("disassociate:4A:F1:89:49:3C:85"), pairing.snapshot())
        assertEquals(null, vm.uiState.value.device)
    }

    @Test
    fun `forgetting a sensor touches neither bond nor association`() = runTest {
        val sensor = repo.addDevice(
            displayName = "Chest strap",
            address = "AA:BB:CC:DD:EE:FF",
            bluetoothName = "Wahoo TICKR",
            capabilities = setOf(BleSensorCapability.HEART_RATE),
        )
        val vm = viewModel(sensor.id)

        vm.removeDevice()

        assertTrue(repo.devices.isEmpty())
        assertTrue(pairing.snapshot().isEmpty())
    }

    @Test
    fun `automatic sync starts off and shows the interval that was picked`() = runTest {
        val watch = addWatch()
        val vm = viewModel(watch.id)

        assertEquals(AutoSyncInterval.OFF, vm.uiState.value.autoSync)

        vm.setAutoSync(AutoSyncInterval.HOURLY)

        // Read back through the scheduler: the row must show what is actually scheduled.
        assertEquals(AutoSyncInterval.HOURLY, vm.uiState.value.autoSync)
        verify { autoSyncScheduler.setInterval(watch.id, AutoSyncInterval.HOURLY) }
    }

    @Test
    fun `forgetting a watch stops its automatic sync`() = runTest {
        val watch = addWatch()
        val vm = viewModel(watch.id)
        vm.setAutoSync(AutoSyncInterval.EVERY_30_MINUTES)

        vm.removeDevice()

        // A schedule outliving the watch would wake the radio for nothing.
        verify { autoSyncScheduler.forget(watch.id) }
    }

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
        val vm = viewModel(watch.id, manager)
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
