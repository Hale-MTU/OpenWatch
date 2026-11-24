package tech.mmarca.openwatch.features.settings

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import tech.mmarca.healthcalculations.preferences.BodyProfile
import tech.mmarca.openlink.OpenLink
import tech.mmarca.openlink.OpenLinkTrust
import tech.mmarca.openlink.host.OpenLinkClientRecord
import tech.mmarca.openlink.host.OpenLinkHostCore
import tech.mmarca.openwatch.data.repository.BodyProfileProvider
import tech.mmarca.openwatch.data.repository.OpenWatchPreferences
import tech.mmarca.openwatch.devices.FakeSharedPreferences
import tech.mmarca.openwatch.domain.preferences.SystemUnitSystemProvider
import tech.mmarca.openwatch.domain.preferences.UnitSystem
import tech.mmarca.openwatch.link.LinkProfileSync
import tech.mmarca.openwatch.util.MainDispatcherRule

/** The body profile shows what was declared, Health Connect's newer measurements, and who it is shared with. */
class BodyProfileViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val preferences = OpenWatchPreferences(FakeSharedPreferences(), SystemUnitSystemProvider { UnitSystem.METRIC })
    private val provider = mockk<BodyProfileProvider>()
    private val clientRecords = MutableStateFlow<List<OpenLinkClientRecord>>(emptyList())
    private val core = mockk<OpenLinkHostCore> { every { clients } returns clientRecords }
    private val sync = mockk<LinkProfileSync> { every { updatedAtMillis } returns MutableStateFlow(0L) }

    private fun viewModel() = BodyProfileViewModel(preferences, provider, { packageName -> "label of $packageName" }, core, sync)

    @Test
    fun `a newer weight from Health Connect shows beside the declared one`() = runTest {
        preferences.setBodyProfile(BodyProfile(weightKg = 70.0, heightCm = 180.0))
        coEvery { provider.resolve() } returns BodyProfile(weightKg = 72.5, heightCm = 180.0)
        val vm = viewModel()

        vm.refresh()

        assertEquals(72.5, vm.uiState.value.measuredWeightKg)
        assertNull(vm.uiState.value.measuredHeightCm)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `a measurement that cannot be read is shown as a failure, and the declared profile stays`() = runTest {
        preferences.setBodyProfile(BodyProfile(weightKg = 70.0))
        coEvery { provider.resolve() } throws IllegalStateException("Health Connect is down")
        val vm = viewModel()

        vm.refresh()

        assertNotNull(vm.uiState.value.error)
        assertEquals(70.0, vm.uiState.value.declared.weightKg)
    }

    @Test
    fun `the app the profile is shared with is named, once it is trusted with the profile`() = runTest {
        val vm = viewModel()
        assertNull(vm.uiState.value.syncedWith)

        clientRecords.value = listOf(
            OpenLinkClientRecord("org.example.pending", "aa", OpenLinkTrust.PENDING, requested = setOf(OpenLink.CAPABILITY_PROFILE)),
            OpenLinkClientRecord("tech.mmarca.openvitals", "bb", OpenLinkTrust.TRUSTED, capabilities = setOf(OpenLink.CAPABILITY_PROFILE)),
        )

        assertEquals("label of tech.mmarca.openvitals", vm.uiState.value.syncedWith)
    }
}
