package tech.mmarca.openwatch.features.settings

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
import tech.mmarca.openwatch.data.repository.BodyProfileProvider
import tech.mmarca.openwatch.data.repository.OpenWatchPreferences
import tech.mmarca.openwatch.domain.preferences.BodyProfile
import tech.mmarca.openwatch.domain.preferences.UnitSystem

data class BodyProfileUiState(
    val declared: BodyProfile = BodyProfile(),
    /** Health Connect's newer weight and height, when their read is granted; the estimates use these. */
    val measuredWeightKg: Double? = null,
    val measuredHeightCm: Double? = null,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
)

@HiltViewModel
class BodyProfileViewModel @Inject constructor(
    private val preferences: OpenWatchPreferences,
    private val bodyProfile: BodyProfileProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        BodyProfileUiState(declared = preferences.bodyProfile(), unitSystem = preferences.unitSystem),
    )
    val uiState: StateFlow<BodyProfileUiState> = _uiState.asStateFlow()

    fun refresh() {
        _uiState.update { it.copy(declared = preferences.bodyProfile(), unitSystem = preferences.unitSystem) }
        viewModelScope.launch {
            val resolved = try {
                bodyProfile.resolve()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return@launch
            }
            val declared = preferences.bodyProfile()
            _uiState.update {
                it.copy(
                    measuredWeightKg = resolved.weightKg.takeIf { w -> w != declared.weightKg },
                    measuredHeightCm = resolved.heightCm.takeIf { h -> h != declared.heightCm },
                )
            }
        }
    }

    fun save(profile: BodyProfile) {
        preferences.setBodyProfile(profile)
        _uiState.update { it.copy(declared = preferences.bodyProfile()) }
    }
}
