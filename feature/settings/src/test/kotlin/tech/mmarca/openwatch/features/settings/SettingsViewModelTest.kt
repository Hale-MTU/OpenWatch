package tech.mmarca.openwatch.features.settings

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tech.mmarca.openwatch.data.repository.OpenWatchPreferences
import tech.mmarca.openwatch.devices.FakeSharedPreferences
import tech.mmarca.openwatch.domain.preferences.SharedUnitChoice
import tech.mmarca.openwatch.domain.preferences.SystemUnitSystemProvider
import tech.mmarca.openwatch.domain.preferences.UnitSystem
import tech.mmarca.openwatch.domain.preferences.UnitSystemPreference
import tech.mmarca.openwatch.features.watches.WatchNotificationsGateway
import tech.mmarca.openwatch.util.MainDispatcherRule

/** Settings shows what is stored and what Android granted, and writes the switches through. */
class SettingsViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val preferences = OpenWatchPreferences(FakeSharedPreferences(), SystemUnitSystemProvider { UnitSystem.METRIC })
    private var accessGranted = false
    private val notifications = mockk<WatchNotificationsGateway> { every { isNotificationAccessGranted() } answers { accessGranted } }

    @Test
    fun `a refresh reads the switches and whether notification access was granted`() {
        val vm = SettingsViewModel(preferences, notifications, versionName = "0.4.1")
        preferences.healthConnectSyncEnabled = false
        accessGranted = true

        vm.refresh()

        assertFalse(vm.uiState.value.syncEnabled)
        assertTrue(vm.uiState.value.notificationAccessGranted)
    }

    @Test
    fun `the switches are stored`() {
        val vm = SettingsViewModel(preferences, notifications, versionName = "0.4.1")

        vm.setSyncEnabled(false)
        vm.setWriteRespiratoryRate(false)
        vm.setWriteSleepBesideOthers(false)

        assertFalse(preferences.healthConnectSyncEnabled)
        assertFalse(preferences.writeRespiratoryRate)
        assertFalse(vm.uiState.value.writeRespiratoryRate)
        assertFalse(preferences.writeSleepBesideOthers)
        assertFalse(vm.uiState.value.writeSleepBesideOthers)
    }

    @Test
    fun `the breathing rate and the night beside other apps' are written until switched off`() {
        val vm = SettingsViewModel(preferences, notifications, versionName = "0.4.1")

        assertTrue(vm.uiState.value.writeRespiratoryRate)
        assertTrue(vm.uiState.value.writeSleepBesideOthers)
    }

    @Test
    fun `OpenWatch's own units are the region's until one is picked, and a pick is kept`() {
        val vm = SettingsViewModel(preferences, notifications, versionName = "0.4.1")
        assertEquals(UnitSystemPreference.SYSTEM, vm.uiState.value.unitSystemPreference)
        assertEquals(UnitSystem.METRIC, vm.uiState.value.unitSystem)

        vm.setUnitSystemPreference(UnitSystemPreference.IMPERIAL)

        assertEquals(UnitSystemPreference.IMPERIAL, vm.uiState.value.unitSystemPreference)
        assertEquals(UnitSystem.IMPERIAL, preferences.unitSystem)
        assertNull(vm.uiState.value.unitsDecidedBy)
    }

    @Test
    fun `units a connected app decides win, and cannot be picked here until it no longer decides`() {
        var decided: SharedUnitChoice? = SharedUnitChoice(UnitSystemPreference.IMPERIAL, "OpenVitals")
        val prefs = OpenWatchPreferences(FakeSharedPreferences(), SystemUnitSystemProvider { UnitSystem.METRIC }) { decided }
        val vm = SettingsViewModel(prefs, notifications, versionName = "0.4.1")
        assertEquals("OpenVitals", vm.uiState.value.unitsDecidedBy)
        assertEquals(UnitSystemPreference.IMPERIAL, vm.uiState.value.unitSystemPreference)

        vm.setUnitSystemPreference(UnitSystemPreference.METRIC)
        assertEquals(UnitSystem.IMPERIAL, prefs.unitSystem)

        decided = null
        vm.refresh()
        assertNull(vm.uiState.value.unitsDecidedBy)
        assertEquals(UnitSystemPreference.SYSTEM, vm.uiState.value.unitSystemPreference)
        assertEquals(UnitSystem.METRIC, prefs.unitSystem)
    }
}
