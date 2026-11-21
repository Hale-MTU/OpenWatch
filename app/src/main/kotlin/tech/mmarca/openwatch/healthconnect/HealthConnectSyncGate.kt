package tech.mmarca.openwatch.healthconnect

import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

class HealthConnectSyncDisabledException : IllegalStateException("Health Connect sync is paused")

/**
 * The user's "write to Health Connect" switch, as the gate reads it. A port, so this
 * package does not import the preferences store, which imports this package.
 */
interface HealthConnectSyncSwitch {
    val healthConnectSyncEnabled: Boolean
    val healthConnectSyncEnabledFlow: StateFlow<Boolean>
}

@Singleton
class HealthConnectSyncGate @Inject constructor(
    private val preferencesRepository: HealthConnectSyncSwitch,
) {
    val isEnabled: Boolean
        get() = preferencesRepository.healthConnectSyncEnabled

    val isEnabledFlow: StateFlow<Boolean>
        get() = preferencesRepository.healthConnectSyncEnabledFlow

    fun requireEnabled() {
        if (!isEnabled) throw HealthConnectSyncDisabledException()
    }
}
