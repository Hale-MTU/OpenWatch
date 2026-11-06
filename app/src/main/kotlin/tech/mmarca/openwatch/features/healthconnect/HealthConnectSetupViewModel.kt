package tech.mmarca.openwatch.features.healthconnect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.mmarca.openwatch.data.repository.OpenWatchPreferences
import tech.mmarca.openwatch.domain.model.HealthConnectAvailability
import tech.mmarca.openwatch.healthconnect.HealthConnectGateway

data class HealthConnectSetupUiState(
    val availability: HealthConnectAvailability = HealthConnectAvailability.NOT_SUPPORTED,
    /** Of the permissions the manifest declares, how many are granted. */
    val grantedCount: Int = 0,
    val declaredCount: Int = 0,
    val syncEnabled: Boolean = true,
    val isLoading: Boolean = true,
) {
    val allGranted: Boolean get() = declaredCount > 0 && grantedCount == declaredCount
}

@HiltViewModel
class HealthConnectSetupViewModel @Inject constructor(
    private val healthConnect: HealthConnectGateway,
    private val preferences: OpenWatchPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HealthConnectSetupUiState(syncEnabled = preferences.healthConnectSyncEnabled))
    val uiState: StateFlow<HealthConnectSetupUiState> = _uiState.asStateFlow()

    /** Every permission the manifest declares: the dialog shows the ones still to grant. */
    val requestablePermissions: Set<String> get() = healthConnect.declaredPermissions

    fun refresh() {
        viewModelScope.launch {
            val availability = healthConnect.availability()
            val granted = if (availability == HealthConnectAvailability.AVAILABLE) {
                try {
                    healthConnect.grantedPermissions()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    emptySet()
                }
            } else {
                emptySet()
            }
            _uiState.update {
                it.copy(
                    availability = availability,
                    grantedCount = (granted intersect healthConnect.declaredPermissions).size,
                    declaredCount = healthConnect.declaredPermissions.size,
                    syncEnabled = preferences.healthConnectSyncEnabled,
                    isLoading = false,
                )
            }
        }
    }

    fun setSyncEnabled(enabled: Boolean) {
        preferences.healthConnectSyncEnabled = enabled
        _uiState.update { it.copy(syncEnabled = enabled) }
    }
}
