package tech.mmarca.openwatch.features.healthconnect

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tech.mmarca.openwatch.data.repository.OpenWatchPreferences
import tech.mmarca.openwatch.devices.FakeSharedPreferences
import tech.mmarca.openwatch.domain.model.HealthConnectAvailability
import tech.mmarca.openwatch.domain.preferences.SystemUnitSystemProvider
import tech.mmarca.openwatch.domain.preferences.UnitSystem
import tech.mmarca.openwatch.healthconnect.HealthConnectGateway
import tech.mmarca.openwatch.util.MainDispatcherRule

/** Setup counts the grants, names the missing ones as Android does, and never reads a failure as none granted. */
class HealthConnectSetupViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val declared = setOf(
        "android.permission.health.WRITE_HEART_RATE",
        "android.permission.health.WRITE_STEPS",
        "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND",
    )
    private val healthConnect = mockk<HealthConnectGateway> {
        every { availability() } returns HealthConnectAvailability.AVAILABLE
        every { declaredPermissions } returns declared
    }
    private val preferences = OpenWatchPreferences(FakeSharedPreferences(), SystemUnitSystemProvider { UnitSystem.METRIC })

    private fun viewModel() = HealthConnectSetupViewModel({ permission -> "label " + permission.substringAfterLast('.') }, healthConnect, preferences)

    @Test
    fun `the missing grants are counted and named, background access last`() = runTest {
        coEvery { healthConnect.grantedPermissions() } returns setOf("android.permission.health.WRITE_STEPS")
        val vm = viewModel()

        vm.refresh()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(1 to 3, state.grantedCount to state.declaredCount)
        assertEquals(listOf("label WRITE_HEART_RATE", "label READ_HEALTH_DATA_IN_BACKGROUND"), state.missing)
        assertTrue(state.missingExtraAccess)
        assertNull(state.error)
    }

    @Test
    fun `grants that cannot be read are a failure, not nothing granted`() = runTest {
        coEvery { healthConnect.grantedPermissions() } throws IllegalStateException("binder died")
        val vm = viewModel()

        vm.refresh()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.error)
        assertTrue(state.missing.isEmpty())
    }
}
