package tech.mmarca.openwatch.features.watches

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.mmarca.openwatch.core.performance.DispatcherProvider
import tech.mmarca.openwatch.core.presentation.ScreenError
import tech.mmarca.openwatch.core.presentation.toScreenError
import tech.mmarca.openwatch.data.repository.WatchNotificationPrefsStore
import tech.mmarca.openwatch.devices.garmin.GarminLog

/** One app the user can silence, with its current blocklist state. */
@Immutable
data class WatchNotificationApp(
    val packageName: String,
    val label: String,
    /** Whether its notifications reach the watch, under the current mode. */
    val sends: Boolean,
)

/**
 * Whether notifications are mirrored, and whether Android allows it. Two
 * gates shown separately, or a flip that does nothing looks like a bug.
 */
@Immutable
data class WatchNotificationsUiState(
    /** Whether the user has switched forwarding on. */
    val enabled: Boolean = false,
    /** Whether Android granted notification access. Only the settings screen can, so it is polled. */
    val accessGranted: Boolean = false,
    /** Whether the user accepted the disclosure. Required before access is requested. */
    val disclosureAccepted: Boolean = false,
    /** Whether the disclosure dialog is on screen. Owned here so the ordering is testable. */
    val showDisclosure: Boolean = false,
    /** Only the apps switched on send, instead of every app but those switched off. */
    val onlyAllowed: Boolean = false,
    /** A channel set to hide sensitive content on the lock screen sends its title only. */
    val hideSensitiveContent: Boolean = true,
    /** Every launchable app, and whether it sends. Empty until loaded. */
    val apps: List<WatchNotificationApp> = emptyList(),
    val loadingApps: Boolean = false,
    val loading: Boolean = true,
    /** The app list could not be read: shown as such, not as a phone without apps. */
    val appsError: ScreenError? = null,
) {
    /** Forwarding is only actually happening when both gates are open. */
    val active: Boolean get() = enabled && accessGranted

    /** In the default mode, the apps switched off. */
    val blockedCount: Int get() = apps.count { !it.sends }

    /** In allow-list mode, the apps switched on. */
    val sendingCount: Int get() = apps.count { it.sends }
}

/**
 * The enable flow enforces, in order, the prominent disclosure (required
 * before notification access is requested) and the settings-screen grant.
 */
@HiltViewModel
class WatchNotificationAppsViewModel @Inject constructor(
    private val store: WatchNotificationPrefsStore,
    private val gateway: WatchNotificationsGateway,
    dispatchers: DispatcherProvider,
) : ViewModel() {

    private val ioDispatcher: CoroutineContext = dispatchers.io

    private val state = MutableStateFlow(WatchNotificationsUiState())
    val uiState: StateFlow<WatchNotificationsUiState> = state.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads the permission. Android gives no callback, so the screen calls this on resume. */
    fun refresh() {
        viewModelScope.launch {
            val granted = readAccess()
            state.update {
                it.copy(
                    enabled = store.enabled,
                    accessGranted = granted,
                    disclosureAccepted = store.disclosureAccepted,
                    onlyAllowed = store.onlyAllowed,
                    hideSensitiveContent = store.hideSensitiveContent,
                    loading = false,
                )
            }
            // Told on every refresh, so the bridge lets go of a link it no longer needs: the
            // paired watch can change without this switch moving.
            pushConfig()
        }
    }

    /** Flips forwarding. Turning on runs the gates: disclosure, then access, then the preference. */
    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (!enabled) {
                store.enabled = false
                state.update { it.copy(enabled = false) }
                pushConfig()
                return@launch
            }

            // Consent before the permission is requested.
            if (!store.disclosureAccepted) {
                state.update { it.copy(showDisclosure = true) }
                return@launch
            }
            proceedWithEnable()
        }
    }

    /** The user accepted the prominent disclosure; the enable flow continues. */
    fun acceptDisclosure() {
        viewModelScope.launch {
            store.disclosureAccepted = true
            state.update { it.copy(disclosureAccepted = true, showDisclosure = false) }
            proceedWithEnable()
        }
    }

    /** Dismissing the dialog counts as declining: nothing is enabled. */
    fun declineDisclosure() {
        state.update { it.copy(showDisclosure = false) }
    }

    private suspend fun proceedWithEnable() {
        // Re-read rather than trust the cache: Android gives no callback when access is granted.
        var granted = state.value.accessGranted
        if (!granted) {
            granted = readAccess()
            state.update { it.copy(accessGranted = granted) }
        }
        if (!granted) {
            gateway.openNotificationAccessSettings()
            return
        }

        store.enabled = true
        state.update { it.copy(enabled = true) }
        pushConfig()
    }

    /** Loads the app list. */
    fun loadApps() {
        if (state.value.loadingApps) return
        state.update { it.copy(loadingApps = true, appsError = null) }
        viewModelScope.launch {
            val installed = withContext(ioDispatcher) {
                try {
                    gateway.listLaunchableApps()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    state.update { it.copy(appsError = error.toScreenError(logTag = "WatchNotificationApps")) }
                    emptyList()
                }
            }
            state.update {
                it.copy(
                    loadingApps = false,
                    apps = installed.map { app ->
                        WatchNotificationApp(
                            packageName = app.packageName,
                            label = app.label,
                            sends = store.sends(app.packageName),
                        )
                    },
                )
            }
        }
    }

    /** Lets an app send, or stops it, in the list the current mode reads. */
    fun setSends(packageName: String, sends: Boolean) {
        viewModelScope.launch {
            store.setSends(packageName, sends = sends)
            state.update {
                it.copy(
                    apps = it.apps.map { app ->
                        if (app.packageName == packageName) app.copy(sends = sends) else app
                    },
                )
            }
            pushConfig()
        }
    }

    /** Switches between every app but the silenced ones and only the chosen ones. Each list is kept. */
    fun setOnlyAllowed(onlyAllowed: Boolean) {
        viewModelScope.launch {
            store.onlyAllowed = onlyAllowed
            state.update { current ->
                current.copy(
                    onlyAllowed = onlyAllowed,
                    apps = current.apps.map { app -> app.copy(sends = store.sends(app.packageName)) },
                )
            }
            pushConfig()
        }
    }

    fun setHideSensitiveContent(hide: Boolean) {
        store.hideSensitiveContent = hide
        state.update { it.copy(hideSensitiveContent = hide) }
    }

    fun openAccessSettings() {
        gateway.openNotificationAccessSettings()
    }

    private suspend fun pushConfig() {
        withContext(ioDispatcher) {
            try {
                gateway.pushConfiguration()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                GarminLog.log("[GARMIN-NOTIFY] could not push the config: $error")
            }
        }
    }

    private suspend fun readAccess(): Boolean = withContext(ioDispatcher) {
        try {
            gateway.isNotificationAccessGranted()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            GarminLog.log("[GARMIN-NOTIFY] could not read notification access: $error")
            false
        }
    }
}
