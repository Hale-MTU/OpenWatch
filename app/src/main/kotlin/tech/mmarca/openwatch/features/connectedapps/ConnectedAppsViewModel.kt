package tech.mmarca.openwatch.features.connectedapps

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tech.mmarca.openlink.OpenLink
import tech.mmarca.openlink.OpenLinkTrust
import tech.mmarca.openlink.host.OpenLinkClientRecord
import tech.mmarca.openlink.host.OpenLinkHostCore

/** One app that asked to use OpenWatch over OpenLink, as the screen shows it. */
data class ConnectedApp(
    val packageName: String,
    val label: String,
    val trust: OpenLinkTrust,
    /** What it asked for, in the order the screen lists them. */
    val requested: List<String>,
    val granted: Set<String>,
)

@HiltViewModel
class ConnectedAppsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val core: OpenLinkHostCore,
) : ViewModel() {

    val apps: StateFlow<List<ConnectedApp>> = core.clients
        .map { records -> records.map { it.toConnectedApp() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun allow(packageName: String) = core.allow(packageName)

    fun block(packageName: String) = core.block(packageName)

    /** Forgets the app: a blocked one may ask again, a trusted one must. */
    fun forget(packageName: String) = core.forget(packageName)

    fun setCapability(packageName: String, capability: String, granted: Boolean) {
        if (granted) core.grant(packageName, capability) else core.revoke(packageName, capability)
    }

    private fun OpenLinkClientRecord.toConnectedApp() = ConnectedApp(
        packageName = packageName,
        label = appLabel(context, packageName),
        trust = trust,
        requested = CapabilityOrder.filter { it in requested },
        granted = capabilities,
    )

    companion object {
        val CapabilityOrder = listOf(
            OpenLink.CAPABILITY_DEVICES,
            OpenLink.CAPABILITY_PROFILE,
            OpenLink.CAPABILITY_RECORDING,
            OpenLink.CAPABILITY_LIVE,
        )
    }
}

/** The app's name as the launcher shows it, or its package name if it is not visible. */
internal fun appLabel(context: Context, packageName: String): String = runCatching {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getApplicationInfo(packageName, 0)
    }
    pm.getApplicationLabel(info).toString()
}.getOrDefault(packageName)
