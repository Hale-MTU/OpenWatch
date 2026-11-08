package tech.mmarca.openwatch.features.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.mmarca.openlink.OpenLink
import tech.mmarca.openlink.OpenLinkTrust
import tech.mmarca.openlink.host.OpenLinkHostCore
import tech.mmarca.openwatch.data.repository.BodyProfileProvider
import tech.mmarca.openwatch.data.repository.OpenWatchPreferences
import tech.mmarca.openwatch.domain.preferences.BodyProfile
import tech.mmarca.openwatch.domain.preferences.UnitSystem
import tech.mmarca.openwatch.features.connectedapps.appLabel
import tech.mmarca.openwatch.link.LinkProfileSync

data class BodyProfileUiState(
    val declared: BodyProfile = BodyProfile(),
    /** Health Connect's newer weight and height, when their read is granted; the estimates use these. */
    val measuredWeightKg: Double? = null,
    val measuredHeightCm: Double? = null,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    /** The app the profile is kept in sync with over OpenLink, by name; null if none may. */
    val syncedWith: String? = null,
    /** When the profile last changed on either side of that sync. */
    val syncUpdatedAtMillis: Long = 0L,
)

@HiltViewModel
class BodyProfileViewModel @Inject constructor(
    private val preferences: OpenWatchPreferences,
    private val bodyProfile: BodyProfileProvider,
    @ApplicationContext private val context: Context,
    linkCore: OpenLinkHostCore,
    profileSync: LinkProfileSync,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        BodyProfileUiState(declared = preferences.bodyProfile(), unitSystem = preferences.unitSystem),
    )
    val uiState: StateFlow<BodyProfileUiState> = _uiState.asStateFlow()

    init {
        // A profile another app pushed shows at once, and so does who it is synced with.
        viewModelScope.launch {
            preferences.bodyProfileFlow.collect { profile -> _uiState.update { it.copy(declared = profile) } }
        }
        viewModelScope.launch {
            combine(linkCore.clients, profileSync.updatedAtMillis) { clients, updatedAt ->
                val peer = clients.firstOrNull { it.trust == OpenLinkTrust.TRUSTED && OpenLink.CAPABILITY_PROFILE in it.capabilities }
                peer?.let { appLabel(context, it.packageName) } to updatedAt
            }.collect { (peer, updatedAt) -> _uiState.update { it.copy(syncedWith = peer, syncUpdatedAtMillis = updatedAt) } }
        }
    }

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
