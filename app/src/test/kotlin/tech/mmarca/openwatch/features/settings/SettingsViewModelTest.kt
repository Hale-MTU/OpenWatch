package tech.mmarca.openwatch.features.settings

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tech.mmarca.openwatch.data.repository.OpenWatchPreferences
import tech.mmarca.openwatch.devices.FakeSharedPreferences
import tech.mmarca.openwatch.domain.preferences.SystemUnitSystemProvider
import tech.mmarca.openwatch.domain.preferences.UnitSystem
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
        val vm = SettingsViewModel(preferences, notifications)
        preferences.healthConnectSyncEnabled = false
        accessGranted = true

        vm.refresh()

        assertFalse(vm.uiState.value.syncEnabled)
        assertTrue(vm.uiState.value.notificationAccessGranted)
    }

    @Test
    fun `the switches are stored`() {
        val vm = SettingsViewModel(preferences, notifications)

        vm.setSyncEnabled(false)
        vm.setWriteRespiratoryRate(true)

        assertFalse(preferences.healthConnectSyncEnabled)
        assertTrue(preferences.writeRespiratoryRate)
        assertTrue(vm.uiState.value.writeRespiratoryRate)
    }
}
