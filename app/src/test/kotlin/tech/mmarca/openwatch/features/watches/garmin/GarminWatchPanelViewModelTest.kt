package tech.mmarca.openwatch.features.watches.garmin

import android.Manifest
import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.mmarca.openwatch.comaps.CoMapsGuidanceFeed
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.data.repository.testDeviceRepository
import tech.mmarca.openwatch.data.repository.testDeviceStateStore
import tech.mmarca.openwatch.devices.FakeSharedPreferences
import tech.mmarca.openwatch.devices.garmin.GarminAgpsState
import tech.mmarca.openwatch.devices.garmin.GarminAgpsStore
import tech.mmarca.openwatch.devices.garmin.GarminCapability
import tech.mmarca.openwatch.devices.garmin.GarminDeviceStateStore
import tech.mmarca.openwatch.devices.garmin.GarminNotificationBridge
import tech.mmarca.openwatch.devices.garmin.GarminRealtimeStore
import tech.mmarca.openwatch.devices.garmin.GarminWatchOptions
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration
import tech.mmarca.openwatch.features.watches.GarminWatchActionsController
import tech.mmarca.openwatch.features.watches.WatchFindUiState
import tech.mmarca.openwatch.features.watches.WatchNotificationsGateway
import tech.mmarca.openwatch.features.watches.WatchPermissionRequest
import tech.mmarca.openwatch.navigation.WatchDevice
import tech.mmarca.openwatch.navigation.routeHandle
import tech.mmarca.openwatch.util.MainDispatcherRule

/** The Garmin panel: the music disclosure, the calendar grant, where alarms lead, and switches that follow the store. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GarminWatchPanelViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo: BleDeviceRepository = testDeviceRepository()
    private val stateStore = GarminDeviceStateStore(testDeviceStateStore(), FakeSharedPreferences())
    private val notificationsGateway = mockk<WatchNotificationsGateway>(relaxed = true)
    private val notificationBridge = mockk<GarminNotificationBridge>(relaxed = true)

    private fun addWatch(): BleSensorDevice = repo.addDevice(
        displayName = "vívoactive 5",
        address = "E0:48:24:D5:F7:10",
        bluetoothName = "vívoactive 5",
        capabilities = emptySet(),
        kind = BleDeviceKind.WATCH,
        integration = DeviceIntegration.GARMIN,
    )

    private fun viewModel(deviceId: String) = GarminWatchPanelViewModel(
        savedStateHandle = routeHandle(WatchDevice(deviceId)),
        options = GarminWatchOptions(
            deviceRepository = repo,
            stateStore = stateStore,
            bridge = notificationBridge,
            navigationRelay = mockk(relaxed = true),
            musicRelay = mockk(relaxed = true),
            calendarSource = mockk(relaxed = true),
            coMaps = mockk(relaxed = true),
            guidanceFeed = CoMapsGuidanceFeed(mockk(relaxed = true), CoroutineScope(SupervisorJob())),
            realtime = GarminRealtimeStore(),
        ),
        agpsStore = mockk<GarminAgpsStore>(relaxed = true).also {
            every { it.agps } returns MutableStateFlow(GarminAgpsState())
        },
        actionsController = mockk<GarminWatchActionsController>().also {
            every { it.state } returns MutableStateFlow(WatchFindUiState())
        },
        notificationsGateway = notificationsGateway,
        dispatchers = mainDispatcherRule.dispatcherProvider,
    )

    @Test
    fun `switching music controls on without notification access shows the disclosure first`() = runTest {
        val watch = addWatch()
        every { notificationsGateway.isNotificationAccessGranted() } returns false
        val vm = viewModel(watch.id)
        backgroundScope.launch { vm.uiState.collect { } }

        vm.setMusicControls(true)
        runCurrent()

        assertTrue(vm.uiState.value.showMusicDisclosure)
        verify(exactly = 0) { notificationBridge.onMusicControlsChanged(any(), any()) }
        verify(exactly = 0) { notificationsGateway.openNotificationAccessSettings() }
    }

    @Test
    fun `accepting the disclosure switches on and opens Android's settings, declining does neither`() = runTest {
        val watch = addWatch()
        every { notificationsGateway.isNotificationAccessGranted() } returns false
        val vm = viewModel(watch.id)
        backgroundScope.launch { vm.uiState.collect { } }

        vm.setMusicControls(true)
        vm.declineMusicDisclosure()
        runCurrent()
        assertFalse(vm.uiState.value.showMusicDisclosure)
        verify(exactly = 0) { notificationBridge.onMusicControlsChanged(any(), any()) }

        vm.setMusicControls(true)
        vm.acceptMusicDisclosure()
        runCurrent()
        assertFalse(vm.uiState.value.showMusicDisclosure)
        verify(exactly = 1) { notificationBridge.onMusicControlsChanged(watch.id, true) }
        verify(exactly = 1) { notificationsGateway.openNotificationAccessSettings() }
    }

    @Test
    fun `with access granted the switch applies at once, and off never asks`() = runTest {
        val watch = addWatch()
        every { notificationsGateway.isNotificationAccessGranted() } returns true
        val vm = viewModel(watch.id)

        vm.setMusicControls(true)
        every { notificationsGateway.isNotificationAccessGranted() } returns false
        vm.setMusicControls(false)

        verify(exactly = 1) { notificationBridge.onMusicControlsChanged(watch.id, true) }
        verify(exactly = 1) { notificationBridge.onMusicControlsChanged(watch.id, false) }
        verify(exactly = 0) { notificationsGateway.openNotificationAccessSettings() }
    }

    @Test
    fun `calendar on asks for the calendar grant, and a refusal still sets the switch`() = runTest {
        val watch = addWatch()
        val vm = viewModel(watch.id)

        vm.toggleCalendarSync(true)

        assertEquals(
            WatchPermissionRequest.Runtime(Manifest.permission.READ_CALENDAR),
            vm.uiState.value.permissionRequest,
        )
        // Nothing is set until the dialog answers.
        assertFalse(vm.uiState.value.calendarSync)

        vm.onPermissionRequestHandled()
        // Refused: the calendar source still has no grant.
        vm.onRuntimePermissionAnswered()

        assertNull(vm.uiState.value.permissionRequest)
        assertTrue(stateStore.calendarSync(watch.id))
        assertTrue(vm.uiState.value.calendarSync)
        // The row says what is missing.
        assertTrue(vm.uiState.value.calendarPermissionMissing)
    }

    @Test
    fun `a switch changed elsewhere shows at once, with no change made here`() = runTest {
        val watch = addWatch()
        val vm = viewModel(watch.id)
        assertTrue(vm.uiState.value.stayConnected)

        // The bridge, a relay or OpenLink writes the store; the screen reads it back.
        stateStore.setStayConnected(watch.id, false)
        stateStore.setHighMtu(watch.address, false)

        assertFalse(vm.uiState.value.stayConnected)
        assertFalse(vm.uiState.value.highMtu)
    }

    @Test
    fun `alarms open in the watch's settings tree when it has one, else the phone's list`() {
        // Unknown capabilities count as present, so a watch never synced gets the tree.
        assertEquals(AlarmsDestination.SETTINGS_TREE, GarminWatchPanelUiState().alarmsDestination)
        assertEquals(
            AlarmsDestination.SETTINGS_TREE,
            GarminWatchPanelUiState(capabilities = setOf(GarminCapability.REALTIME_SETTINGS)).alarmsDestination,
        )
        assertEquals(
            AlarmsDestination.PHONE_LIST,
            GarminWatchPanelUiState(capabilities = setOf(GarminCapability.SYNC)).alarmsDestination,
        )
    }
}
