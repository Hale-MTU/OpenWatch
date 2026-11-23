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
import tech.mmarca.openwatch.core.presentation.ScreenError
import tech.mmarca.openwatch.core.presentation.toScreenError
import tech.mmarca.openwatch.data.repository.OpenWatchPreferences
import tech.mmarca.openwatch.domain.model.HealthConnectAvailability
import tech.mmarca.openwatch.healthconnect.HealthConnectGateway

data class HealthConnectSetupUiState(
    val availability: HealthConnectAvailability = HealthConnectAvailability.NOT_SUPPORTED,
    /** Of the permissions the manifest declares, how many are granted. */
    val grantedCount: Int = 0,
    val declaredCount: Int = 0,
    /** The ones not granted, by the name Android gives them, background and history last. */
    val missing: List<String> = emptyList(),
    /** Whether a missing one is background or history access, which sits apart in Health Connect. */
    val missingExtraAccess: Boolean = false,
    val syncEnabled: Boolean = true,
    val isLoading: Boolean = true,
    /** The grants could not be read. Shown as such, never as nothing granted. */
    val error: ScreenError? = null,
) {
    val allGranted: Boolean get() = declaredCount > 0 && grantedCount == declaredCount
}

@HiltViewModel
class HealthConnectSetupViewModel @Inject constructor(
    private val permissionLabels: HealthPermissionLabels,
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
                    val screenError = error.toScreenError(logTag = "HealthConnectSetup")
                    _uiState.update { it.copy(availability = availability, isLoading = false, error = screenError) }
                    return@launch
                }
            } else {
                emptySet()
            }
            val missing = if (availability == HealthConnectAvailability.AVAILABLE) {
                missingPermissions(healthConnect.declaredPermissions, granted)
            } else {
                emptyList()
            }
            _uiState.update {
                it.copy(
                    availability = availability,
                    missing = missing.map(permissionLabels::label),
                    missingExtraAccess = missing.any { permission -> permission in ExtraAccess },
                    grantedCount = (granted intersect healthConnect.declaredPermissions).size,
                    declaredCount = healthConnect.declaredPermissions.size,
                    syncEnabled = preferences.healthConnectSyncEnabled,
                    isLoading = false,
                    error = null,
                )
            }
        }
    }

    fun setSyncEnabled(enabled: Boolean) {
        preferences.healthConnectSyncEnabled = enabled
        _uiState.update { it.copy(syncEnabled = enabled) }
    }
}

/** Health Connect's background and history access: granted on their own page, not with the types. */
internal val ExtraAccess = setOf(
    "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND",
    "android.permission.health.READ_HEALTH_DATA_HISTORY",
)

/** The declared permissions not granted: the data types first, in name order, then the extra access. */
internal fun missingPermissions(declared: Set<String>, granted: Set<String>): List<String> =
    (declared - granted).sortedWith(compareBy<String> { it in ExtraAccess }.thenBy { it })
