package tech.mmarca.openwatch.healthconnect

import kotlinx.coroutines.flow.StateFlow
import tech.mmarca.openwatch.data.repository.OpenWatchPreferences
import javax.inject.Inject
import javax.inject.Singleton

class HealthConnectSyncDisabledException : IllegalStateException("Health Connect sync is paused")

@Singleton
class HealthConnectSyncGate @Inject constructor(
    private val preferencesRepository: OpenWatchPreferences,
) {
    val isEnabled: Boolean
        get() = preferencesRepository.healthConnectSyncEnabled

    val isEnabledFlow: StateFlow<Boolean>
        get() = preferencesRepository.healthConnectSyncEnabledFlow

    fun requireEnabled() {
        if (!isEnabled) throw HealthConnectSyncDisabledException()
    }
}
