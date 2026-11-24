package tech.mmarca.openwatch.features.scales

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import android.app.Application
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
import tech.mmarca.openwatch.core.presentation.ScreenError
import tech.mmarca.openwatch.core.presentation.UnitFormatter
import tech.mmarca.openwatch.core.security.testSeal
import tech.mmarca.openwatch.data.repository.contract.FakeScaleWeighInRepository
import tech.mmarca.openwatch.healthconnect.HealthConnectGateway
import tech.mmarca.openwatch.devices.FakeSharedPreferences
import tech.mmarca.openwatch.devices.xiaomi.ScaleListenerStatus
import tech.mmarca.openwatch.devices.xiaomi.ScaleRecordKind
import tech.mmarca.openwatch.devices.xiaomi.ScaleWeighInWriter
import tech.mmarca.openwatch.devices.xiaomi.ScaleWritePermissions
import tech.mmarca.openwatch.devices.xiaomi.XiaomiScaleListener
import tech.mmarca.openwatch.devices.xiaomi.XiaomiScaleStore
import tech.mmarca.healthcalculations.insights.BodyCompositionInput
import tech.mmarca.openwatch.domain.preferences.UnitSystem
import tech.mmarca.openwatch.navigation.DeviceDetail
import tech.mmarca.openwatch.navigation.routeHandle
import tech.mmarca.openwatch.util.MainDispatcherRule

/** The device screen's own decisions over one set-up scale. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScaleDeviceViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val key = "0728974d657a4b60964c1b1677f35f7c".hexToByteArray()
    private val store = XiaomiScaleStore(FakeSharedPreferences(), testSeal()).also { it.setUp("8C:D0:B2:F6:BE:EF", "Bathroom", key) }
    /** The registry's scale row; the screen was opened for "s400-row". */
    private val registryScaleId = MutableStateFlow<String?>("s400-row")
    private val listener = mockk<XiaomiScaleListener>(relaxed = true) {
        every { status } returns MutableStateFlow(ScaleListenerStatus.LISTENING)
        every { deviceId } returns registryScaleId
        every { isWokenBySystem() } returns true
        every { rename(any()) } answers { store.rename(firstArg()) }
        every { changeKey(any()) } answers { store.changeKey(firstArg()) }
        every { forget() } answers { store.clear() }
    }
    private val writer = mockk<ScaleWeighInWriter>(relaxed = true) {
        coEvery { missingProfileInputs() } returns setOf(BodyCompositionInput.SEX)
    }
    private val healthRepository = mockk<HealthConnectGateway> {
        coEvery { grantedPermissions() } returns setOf(ScaleRecordKind.WEIGHT.writePermission)
    }

    private fun viewModel() = ScaleDeviceViewModel(
        savedStateHandle = routeHandle(DeviceDetail("s400-row")),
        store = store,
        listener = listener,
        writer = writer,
        weighIns = FakeScaleWeighInRepository(),
        healthConnect = healthRepository,
        unitFormatter = UnitFormatter(unitSystemProvider = { UnitSystem.METRIC }),
    )

    @Test
    fun `the screen shows the scale as stored, and what Health Connect and the profile still lack`() = runTest {
        val viewModel = viewModel()
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { viewModel.uiState.collect {} }

        viewModel.refresh()

        val state = viewModel.uiState.value
        assertEquals("Bathroom" to "8C:D0:B2:F6:BE:EF", state.name to state.address)
        assertNull("no weigh-in yet", state.profile)
        assertTrue(state.wokenBySystem)
        assertEquals(ScaleWritePermissions - ScaleRecordKind.WEIGHT.writePermission, state.missingWritePermissions)
        assertEquals(setOf(BodyCompositionInput.SEX), state.missingProfileInputs)
    }

    @Test
    fun `renaming trims and ignores an empty name, a key change keeps the scale, and removal forgets it`() = runTest {
        val viewModel = viewModel()
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { viewModel.uiState.collect {} }

        viewModel.rename("  Hall scale ")
        viewModel.rename("   ")
        assertEquals("Hall scale", viewModel.uiState.value.name)

        viewModel.openKeyDialog()
        viewModel.changeKey("nope")
        assertTrue(viewModel.uiState.value.keyDialogOpen)
        assertTrue(viewModel.uiState.value.keyInputInvalid)
        viewModel.onKeyEdited()
        assertFalse(viewModel.uiState.value.keyInputInvalid)
        viewModel.changeKey("ffffffffffffffffffffffffffffffff")
        assertFalse(viewModel.uiState.value.keyDialogOpen)
        verify(exactly = 1) { listener.changeKey(any()) }
        assertEquals("8C:D0:B2:F6:BE:EF", viewModel.uiState.value.address)

        viewModel.remove()
        assertNull(viewModel.uiState.value.name)
        assertTrue(viewModel.uiState.value.removed)
    }

    @Test
    fun `a delete Health Connect refuses for a missing grant shows the grant affordance`() = runTest {
        val repository = FakeScaleWeighInRepository()
        repository.merge(1744250605, 1, tech.mmarca.openwatch.domain.model.ScaleReading(weightKg = 69.9),
            java.time.Instant.ofEpochSecond(1744250605), java.time.Instant.ofEpochSecond(1744250625))
        coEvery { writer.delete(any()) } throws SecurityException("no grant")
        val viewModel = ScaleDeviceViewModel(routeHandle(DeviceDetail("s400-row")), store, listener, writer, repository, healthRepository,
            UnitFormatter(unitSystemProvider = { UnitSystem.METRIC }))
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { viewModel.uiState.collect {} }

        viewModel.deleteLastWeighIn()

        assertEquals(ScreenError.PermissionDenied, viewModel.uiState.value.screenError)
    }

    @Test
    fun `a scale replaced since the screen opened shows as gone, not as this one`() = runTest {
        val viewModel = viewModel()
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { viewModel.uiState.collect {} }
        assertEquals("Bathroom", viewModel.uiState.value.name)

        // Another scale was set up: the registry's row is a new one.
        registryScaleId.value = "another-row"

        assertNull(viewModel.uiState.value.name)
    }
}
