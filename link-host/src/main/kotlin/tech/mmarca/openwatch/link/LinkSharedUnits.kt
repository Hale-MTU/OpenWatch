package tech.mmarca.openwatch.link

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import tech.mmarca.openlink.OpenLink
import tech.mmarca.openlink.OpenLinkSettingKeys
import tech.mmarca.openlink.OpenLinkSettings
import tech.mmarca.openlink.OpenLinkTrust
import tech.mmarca.openlink.host.OpenLinkClientRecord
import tech.mmarca.openlink.host.OpenLinkHostCore
import tech.mmarca.openwatch.core.performance.ApplicationScope
import tech.mmarca.openwatch.domain.preferences.SharedUnitChoice
import tech.mmarca.openwatch.domain.preferences.SharedUnits
import tech.mmarca.openwatch.domain.preferences.UnitSystemPreference

/**
 * The units a connected app decides ([SharedUnits]): OpenLink's `units.system` setting, which
 * OpenVitals sends and OpenWatch never sets itself. It holds while the app that set it is
 * installed, trusted and allowed `settings`; once that app is gone, forgotten, blocked or has the
 * capability taken back, OpenWatch's own choice holds again. Who set it is kept beside the setting.
 *
 * Read from memory: worked out again when the settings, the client records or the installed
 * apps change.
 */
@Singleton
class LinkSharedUnits(
    private val prefs: SharedPreferences,
    private val settings: StateFlow<OpenLinkSettings>,
    clients: StateFlow<List<OpenLinkClientRecord>>,
    private val apps: InstalledApps,
    scope: CoroutineScope,
) : SharedUnits {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        store: LinkSettingsStore,
        host: OpenLinkHostCore,
        apps: InstalledApps,
        @ApplicationScope scope: CoroutineScope,
    ) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), store.settings, host.clients, apps, scope)

    private val decidedBy = MutableStateFlow(prefs.getString(KEY_DECIDED_BY, null))

    private val choice: StateFlow<SharedUnitChoice?> =
        combine(settings, clients, decidedBy, apps.changes.onStart { emit(Unit) }) { held, records, by, _ ->
            decide(held, records, by)
        }.stateIn(scope, SharingStarted.Eagerly, null)

    override fun decided(): SharedUnitChoice? = choice.value

    /**
     * [incoming] came from [packageName] and the settings store has taken it: when it held the
     * units and the store now holds that choice, [packageName] decides them.
     */
    fun onSettingsFrom(packageName: String, incoming: OpenLinkSettings) {
        val sent = incoming.entries[UNITS.name] ?: return
        if (settings.value.entries[UNITS.name]?.values != sent.values) return
        if (decidedBy.value == packageName) return
        prefs.edit { putString(KEY_DECIDED_BY, packageName) }
        decidedBy.value = packageName
    }

    private fun decide(held: OpenLinkSettings, records: List<OpenLinkClientRecord>, by: String?): SharedUnitChoice? {
        val packageName = by ?: return null
        val preference = held.number(UNITS)?.let(::preferenceOf) ?: return null
        val record = records.firstOrNull { it.packageName == packageName } ?: return null
        if (record.trust != OpenLinkTrust.TRUSTED || OpenLink.CAPABILITY_SETTINGS !in record.capabilities) return null
        val label = apps.labelIfInstalled(packageName) ?: return null
        return SharedUnitChoice(preference, label)
    }

    private companion object {
        val UNITS = OpenLinkSettingKeys.UNITS_SYSTEM
        const val PREFS = "openlink_units"
        const val KEY_DECIDED_BY = "decided_by"

        fun preferenceOf(value: Double): UnitSystemPreference? = when (value) {
            OpenLinkSettingKeys.UNITS_BY_REGION -> UnitSystemPreference.SYSTEM
            OpenLinkSettingKeys.UNITS_METRIC -> UnitSystemPreference.METRIC
            OpenLinkSettingKeys.UNITS_IMPERIAL -> UnitSystemPreference.IMPERIAL
            else -> null
        }
    }
}
