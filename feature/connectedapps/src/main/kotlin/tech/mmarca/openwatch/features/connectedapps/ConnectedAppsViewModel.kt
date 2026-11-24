package tech.mmarca.openwatch.features.connectedapps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import tech.mmarca.openlink.OpenLink
import tech.mmarca.openlink.OpenLinkTrust
import tech.mmarca.openlink.host.OpenLinkClientRecord
import tech.mmarca.openlink.host.OpenLinkHost
import tech.mmarca.openlink.host.OpenLinkHostCore
import tech.mmarca.openwatch.link.AppLabelResolver
import tech.mmarca.openwatch.link.FirstPartyApps
import tech.mmarca.openwatch.link.LinkLiveSessions
import tech.mmarca.openwatch.link.LinkSessionStatus
import tech.mmarca.openwatch.link.linkSessionStatuses
import tech.mmarca.openwatch.link.signerFingerprint

/** One app that asked to use OpenWatch over OpenLink, as the screen shows it. */
data class ConnectedApp(
    val packageName: String,
    val label: String,
    val trust: OpenLinkTrust,
    /** What it asked for, in the order the screen lists them. */
    val requested: List<String>,
    val granted: Set<String>,
    /** Bound with a listener now. */
    val inUse: Boolean = false,
    /** When it last connected or went away; 0 if never seen since this was recorded. */
    val lastSeenAtMillis: Long = 0L,
    /** When OpenWatch last took a body profile from it; 0 if never. */
    val profileReceivedAtMillis: Long = 0L,
    /** What its recording sessions are doing to a watch now. */
    val sessions: List<LinkSessionStatus> = emptyList(),
    /** The clock the relative times are read against; ticks while the screen shows. */
    val nowMillis: Long = 0L,
    /** It uses a first-party name but is not signed by a first-party key; the screen warns. */
    val unknownSignerForKnownName: Boolean = false,
    /** Came under a first-party package and key. Any other app shows its package and signer. */
    val firstParty: Boolean = false,
    /** Its signing key, short, for a person to check; see `signerFingerprint`. */
    val signerFingerprint: String = "",
    /** The first-party name its label carries under another package; the screen warns. */
    val lookalikeOf: String? = null,
)

@HiltViewModel
class ConnectedAppsViewModel @Inject constructor(
    private val appLabels: AppLabelResolver,
    private val firstParty: FirstPartyApps,
    private val core: OpenLinkHostCore,
    host: OpenLinkHost,
    liveSessions: LinkLiveSessions,
) : ViewModel() {

    private val statuses = combine(host.activeSessions, liveSessions.activity, ::linkSessionStatuses)

    val apps: StateFlow<List<ConnectedApp>> =
        combine(core.clients, host.connected, statuses, ticker()) { records, connected, sessions, now ->
            records.map { record ->
                record.toConnectedApp().copy(
                    inUse = record.packageName in connected,
                    sessions = sessions.filter { it.packageName == record.packageName },
                    nowMillis = now,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun allow(packageName: String) = core.allow(packageName)

    fun block(packageName: String) = core.block(packageName)

    /** Forgets the app: a blocked one may ask again, a trusted one must. */
    fun forget(packageName: String) = core.forget(packageName)

    fun setCapability(packageName: String, capability: String, granted: Boolean) {
        if (granted) core.grant(packageName, capability) else core.revoke(packageName, capability)
    }

    private fun OpenLinkClientRecord.toConnectedApp() = appLabels.label(packageName).let { label -> ConnectedApp(
        packageName = packageName,
        label = label,
        trust = trust,
        requested = CapabilityOrder.filter { it in requested },
        granted = capabilities,
        lastSeenAtMillis = lastSeenAtMillis,
        profileReceivedAtMillis = profileReceivedAtMillis,
        unknownSignerForKnownName = firstParty.usesNameWithoutKey(packageName, knownSigner),
        firstParty = knownSigner,
        signerFingerprint = signerFingerprint(signerSha256),
        lookalikeOf = firstParty.lookalikeOf(label, packageName),
    ) }

    /** The time now, again every half minute, so "5 min ago" moves on an open screen. */
    private fun ticker(): Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }

    companion object {
        val CapabilityOrder = listOf(
            OpenLink.CAPABILITY_DEVICES,
            OpenLink.CAPABILITY_PROFILE,
            OpenLink.CAPABILITY_RECORDING,
            OpenLink.CAPABILITY_LIVE,
            OpenLink.CAPABILITY_SETTINGS,
        )
    }
}
