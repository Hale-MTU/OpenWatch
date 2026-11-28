package tech.mmarca.openwatch.features.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tech.mmarca.openwatch.core.presentation.AppVersionName
import tech.mmarca.openwatch.data.repository.OpenWatchPreferences
import tech.mmarca.openwatch.domain.preferences.UnitSystem
import tech.mmarca.openwatch.domain.preferences.UnitSystemPreference
import tech.mmarca.openwatch.features.watches.WatchNotificationsGateway

data class SettingsUiState(
    val syncEnabled: Boolean = true,
    /** Whether the system lets this app read notifications, for the watch forwarder. */
    val notificationAccessGranted: Boolean = false,
    val writeRespiratoryRate: Boolean = true,
    val writeSleepBesideOthers: Boolean = true,
    /** The units the screens show: the connected app's choice while it decides them, else OpenWatch's own. */
    val unitSystemPreference: UnitSystemPreference = UnitSystemPreference.SYSTEM,
    /** What [unitSystemPreference] comes to, for "Currently metric" under System. */
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    /** The connected app that decides the units (OpenVitals), or null while OpenWatch's own choice holds. */
    val unitsDecidedBy: String? = null,
    val versionName: String = "",
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: OpenWatchPreferences,
    private val notifications: WatchNotificationsGateway,
    @AppVersionName versionName: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            syncEnabled = preferences.healthConnectSyncEnabled,
            writeRespiratoryRate = preferences.writeRespiratoryRate,
            writeSleepBesideOthers = preferences.writeSleepBesideOthers,
            versionName = versionName,
        ).withUnits(),
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun refresh() {
        _uiState.update {
            it.copy(
                syncEnabled = preferences.healthConnectSyncEnabled,
                writeRespiratoryRate = preferences.writeRespiratoryRate,
                writeSleepBesideOthers = preferences.writeSleepBesideOthers,
                notificationAccessGranted = notifications.isNotificationAccessGranted(),
            ).withUnits()
        }
    }

    /** OpenWatch's own units; ignored while a connected app decides them, as the screen shows. */
    fun setUnitSystemPreference(preference: UnitSystemPreference) {
        if (preferences.unitsDecidedBy() != null) return
        preferences.unitSystemPreference = preference
        _uiState.update { it.withUnits() }
    }

    private fun SettingsUiState.withUnits(): SettingsUiState {
        val decided = preferences.unitsDecidedBy()
        return copy(
            unitSystemPreference = decided?.preference ?: preferences.unitSystemPreference,
            unitSystem = preferences.unitSystem,
            unitsDecidedBy = decided?.appLabel,
        )
    }

    fun setSyncEnabled(enabled: Boolean) {
        preferences.healthConnectSyncEnabled = enabled
        _uiState.update { it.copy(syncEnabled = enabled) }
    }

    fun setWriteRespiratoryRate(enabled: Boolean) {
        preferences.writeRespiratoryRate = enabled
        _uiState.update { it.copy(writeRespiratoryRate = enabled) }
    }

    fun setWriteSleepBesideOthers(enabled: Boolean) {
        preferences.writeSleepBesideOthers = enabled
        _uiState.update { it.copy(writeSleepBesideOthers = enabled) }
    }
}
