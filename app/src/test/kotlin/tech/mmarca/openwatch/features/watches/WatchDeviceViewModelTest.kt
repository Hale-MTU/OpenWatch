package tech.mmarca.openwatch.features.watches

import android.content.Context
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import tech.mmarca.openwatch.core.security.testSeal
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.data.repository.testDeviceRepository
import tech.mmarca.openwatch.data.repository.testDeviceStateStore
import tech.mmarca.openwatch.devices.FakeSharedPreferences
import tech.mmarca.openwatch.devices.core.DeviceFamilies
import tech.mmarca.openwatch.devices.core.pairing.CompanionFilter
import tech.mmarca.openwatch.devices.core.pairing.WatchBondResult
import tech.mmarca.openwatch.devices.core.pairing.WatchPairingPort
import tech.mmarca.openwatch.devices.core.sync.AutoSyncInterval
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncController
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncUiState
import tech.mmarca.openwatch.devices.core.sync.WatchAutoSyncScheduler
import tech.mmarca.openwatch.devices.garmin.GarminCapability
import tech.mmarca.openwatch.devices.garmin.GarminDeviceStateStore
import tech.mmarca.openwatch.devices.garmin.GarminFamily
import tech.mmarca.openwatch.devices.garmin.GarminGattReport
import tech.mmarca.openwatch.devices.garmin.GarminRealtimeStore
import tech.mmarca.openwatch.devices.garmin.GarminTransportProbe
import tech.mmarca.openwatch.devices.garmin.GarminTransportVariant
import tech.mmarca.openwatch.devices.garmin.OnboardGarminWatchUseCase
import tech.mmarca.openwatch.devices.wearos.OnboardWearOsWatchUseCase
import tech.mmarca.openwatch.devices.wearos.WearOsFamily
import tech.mmarca.openwatch.devices.wearos.WearOsLinkStore
import tech.mmarca.openwatch.devices.wearos.WearOsSyncCursorStore
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorCapability
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration
import tech.mmarca.openwatch.navigation.WATCH_DEVICE_ID_ARG
import tech.mmarca.openwatch.sensors.ble.BleSensorCoordinator
import tech.mmarca.openwatch.util.MainDispatcherRule

/** Renaming a watch with no capabilities, its schedule and the grant that asks for, and what forgetting a device does and does not do at the OS level. */
class WatchDeviceViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakePairing : WatchPairingPort {
        val calls = mutableListOf<String>()

        @Volatile
        var bondResult = WatchBondResult.BONDED

        override suspend fun bond(address: String): WatchBondResult = bondResult

        override suspend fun removeBond(address: String) {
            synchronized(calls) { calls.add("removeBond:$address") }
        }

        override suspend fun associateCompanion(
            address: String,
            displayName: String?,
            filter: CompanionFilter,
        ) = true

        override suspend fun disassociateCompanion(address: String) {
            synchronized(calls) { calls.add("disassociate:$address") }
        }

        override fun isCompanionAssociated(address: String): Boolean = true

        fun snapshot(): List<String> = synchronized(calls) { calls.toList() }
    }

    private class FakeProbe : GarminTransportProbe {
        override suspend fun probe(address: String): GarminGattReport = GarminGattReport(
            address = address,
            variant = GarminTransportVariant.V1,
            services = emptyList(),
        )
    }

    private lateinit var repo: BleDeviceRepository
    private lateinit var stateStore: GarminDeviceStateStore
    private lateinit var pairing: FakePairing
    private val garminLocalData = mockk<tech.mmarca.openwatch.devices.garmin.GarminLocalData>(relaxed = true)
    private val healthConnectManager = mockk<tech.mmarca.openwatch.healthconnect.HealthConnectGateway>(relaxed = true)
    private val notificationBridge = mockk<tech.mmarca.openwatch.devices.garmin.GarminNotificationBridge>(relaxed = true)
    private val wearOsLinkStore = WearOsLinkStore(FakeSharedPreferences(), testSeal())

    /** The scheduler owns the stored interval and the WorkManager side, so it is one stub that remembers. */
    private lateinit var autoSyncScheduler: WatchAutoSyncScheduler
    private val autoSyncIntervals = MutableStateFlow(emptyMap<String, AutoSyncInterval>())

    @Before
    fun setUp() {
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns FakeSharedPreferences()
        repo = testDeviceRepository()
        stateStore = GarminDeviceStateStore(testDeviceStateStore(), FakeSharedPreferences())
        pairing = FakePairing()
        autoSyncScheduler = mockk(relaxed = true)
        every { autoSyncScheduler.interval(any()) } answers {
            autoSyncIntervals.value[firstArg()] ?: AutoSyncInterval.OFF
        }
        every { autoSyncScheduler.intervalFlow(any()) } answers {
            val deviceId = firstArg<String>()
            autoSyncIntervals.map { it[deviceId] ?: AutoSyncInterval.OFF }
        }
        every { autoSyncScheduler.setInterval(any(), any()) } answers {
            autoSyncIntervals.update { it + (firstArg<String>() to secondArg<AutoSyncInterval>()) }
        }
    }

    private fun addWatch(): BleSensorDevice = repo.addDevice(
        displayName = "vívoactive 5",
        address = "E0:48:24:D5:F7:10",
        bluetoothName = "vívoactive 5",
        capabilities = emptySet(),
        kind = BleDeviceKind.WATCH,
        integration = DeviceIntegration.GARMIN,
    )

    private val onboardWearOsWatch = mockk<OnboardWearOsWatchUseCase>(relaxed = true)

    private val backgroundReadRequest = WatchPermissionRequest.HealthConnect(
        setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND),
    )

    private fun viewModel(deviceId: String) = WatchDeviceViewModel(
        savedStateHandle = SavedStateHandle(mapOf(WATCH_DEVICE_ID_ARG to deviceId)),
        deviceRepository = repo,
        syncController = mockk<DeviceSyncController>().also {
            every { it.state } returns MutableStateFlow(DeviceSyncUiState())
        },
        autoSyncScheduler = autoSyncScheduler,
        sensorCoordinator = mockk<BleSensorCoordinator>().also {
            every { it.discoveredDevices } returns MutableStateFlow(emptyList())
            every { it.stopScan() } just runs
        },
        healthConnectManager = healthConnectManager,
        families = DeviceFamilies(
            mapOf(
                "GARMIN" to GarminFamily(
                    repo,
                    stateStore,
                    OnboardGarminWatchUseCase(pairing, repo, FakeProbe()),
                    garminLocalData,
                    GarminRealtimeStore(),
                    notificationBridge,
                ),
                "WEAROS" to WearOsFamily(wearOsLinkStore, onboardWearOsWatch, mockk(relaxed = true), WearOsSyncCursorStore(testDeviceStateStore())),
            ),
        ),
        // The cleanup after a removal runs on the default dispatcher. Here it runs at once.
        dispatchers = mainDispatcherRule.dispatcherProvider,
        appScope = CoroutineScope(SupervisorJob()),
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

        assertEquals("vívoactive 5", repo.devices.single().displayName)
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
    fun `removing the last Garmin watch clears what only served a paired watch`() = runTest {
        val watch = addWatch()
        val vm = viewModel(watch.id)
        assertTrue(vm.uiState.value.offersHistoryDeletion)

        vm.removeDevice()

        // The user did not tick the box, so the watch-only history stays.
        coVerify(timeout = 2_000) { garminLocalData.clearAfterLastWatchRemoved(deleteWellnessHistory = false) }
    }

    @Test
    fun `the user can have the watch-only history deleted with the last watch`() = runTest {
        val watch = addWatch()
        val vm = viewModel(watch.id)

        vm.removeDevice(deleteWatchHistory = true)

        coVerify(timeout = 2_000) { garminLocalData.clearAfterLastWatchRemoved(deleteWellnessHistory = true) }
    }

    @Test
    fun `removing one of two Garmin watches clears nothing the other still needs`() = runTest {
        val watch = addWatch()
        repo.addDevice(
            displayName = "Edge 540",
            address = "E0:48:24:00:00:01",
            bluetoothName = "Edge 540",
            capabilities = emptySet(),
            kind = BleDeviceKind.WATCH,
            integration = DeviceIntegration.GARMIN,
        )
        val vm = viewModel(watch.id)
        assertFalse(vm.uiState.value.offersHistoryDeletion)

        vm.removeDevice(deleteWatchHistory = true)

        coVerify(exactly = 0) { garminLocalData.clearAfterLastWatchRemoved(any()) }
    }

    @Test
    fun `forgetting a watch also drops its bond, association and Garmin state`() = runTest {
        val watch = addWatch()
        stateStore.recordSyncedFileKeys(watch.id, listOf("128/49/1"))
        stateStore.recordCapabilities(watch.id, setOf(GarminCapability.SYNC))
        val vm = viewModel(watch.id)

        vm.removeDevice()

        assertTrue(repo.devices.isEmpty())
        assertEquals(
            listOf(
                "disassociate:E0:48:24:D5:F7:10",
                "removeBond:E0:48:24:D5:F7:10",
            ),
            pairing.snapshot(),
        )
        // The registry does not hold Garmin state; the forget path clears it.
        assertTrue(stateStore.syncedFileKeys(watch.id).isEmpty())
        assertTrue(stateStore.capabilities(watch.id).isEmpty())
        assertNull(vm.uiState.value.device)
    }

    @Test
    fun `forgetting a Wear OS watch drops its link token, so adding it again asks on the watch`() = runTest {
        val watch = repo.addDevice(
            displayName = "Galaxy Watch8",
            address = "4A:F1:89:49:3C:85",
            bluetoothName = "Galaxy Watch8 (89FZ)",
            capabilities = emptySet(),
            kind = BleDeviceKind.WATCH,
            integration = DeviceIntegration.WEAROS,
        )
        val token = wearOsLinkStore.tokenFor(watch.address, "A8:D1:62:BE:3A:3B")
        val vm = viewModel(watch.id)

        vm.removeDevice()

        assertTrue(repo.devices.isEmpty())
        assertNotEquals(token, wearOsLinkStore.tokenFor(watch.address, "A8:D1:62:BE:3A:3B"))
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

        vm.selectAutoSync(AutoSyncInterval.HOURLY)

        // Read back through the scheduler: the row must show what is actually scheduled.
        assertEquals(AutoSyncInterval.HOURLY, vm.uiState.value.autoSync)
        verify { autoSyncScheduler.setInterval(watch.id, AutoSyncInterval.HOURLY) }
    }

    @Test
    fun `switching automatic sync on with the background read missing asks Health Connect for it`() = runTest {
        coEvery { healthConnectManager.isBackgroundReadGrantMissing() } returns true
        val watch = addWatch()
        val vm = viewModel(watch.id)
        vm.refreshBackgroundAccess()
        runCurrent()

        vm.selectAutoSync(AutoSyncInterval.HOURLY)

        assertEquals(backgroundReadRequest, vm.uiState.value.permissionRequest)
        // Asked once: the screen acknowledges the launch.
        vm.onPermissionRequestHandled()
        assertNull(vm.uiState.value.permissionRequest)
        // The card's own button asks the same.
        vm.allowBackgroundRead()
        assertEquals(backgroundReadRequest, vm.uiState.value.permissionRequest)
    }

    @Test
    fun `moving between two intervals does not ask for the background read again`() = runTest {
        coEvery { healthConnectManager.isBackgroundReadGrantMissing() } returns true
        val watch = addWatch()
        autoSyncIntervals.value = mapOf(watch.id to AutoSyncInterval.HOURLY)
        val vm = viewModel(watch.id)
        vm.refreshBackgroundAccess()
        runCurrent()

        vm.selectAutoSync(AutoSyncInterval.EVERY_2_HOURS)

        assertEquals(AutoSyncInterval.EVERY_2_HOURS, vm.uiState.value.autoSync)
        assertNull(vm.uiState.value.permissionRequest)
    }

    @Test
    fun `switching automatic sync on with the background read granted asks nothing`() = runTest {
        coEvery { healthConnectManager.isBackgroundReadGrantMissing() } returns false
        val watch = addWatch()
        val vm = viewModel(watch.id)
        vm.refreshBackgroundAccess()
        runCurrent()

        vm.selectAutoSync(AutoSyncInterval.EVERY_30_MINUTES)

        assertNull(vm.uiState.value.permissionRequest)
    }

    @Test
    fun `forgetting a watch stops its automatic sync`() = runTest {
        val watch = addWatch()
        val vm = viewModel(watch.id)
        vm.selectAutoSync(AutoSyncInterval.EVERY_30_MINUTES)

        vm.removeDevice()

        // A schedule outliving the watch would wake the radio for nothing.
        verify { autoSyncScheduler.forget(watch.id) }
    }
}
