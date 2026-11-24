package tech.mmarca.openwatch.link

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.mmarca.openlink.OpenLinkSetting
import tech.mmarca.openlink.OpenLinkSettingKey
import tech.mmarca.openlink.OpenLinkSettingKeys
import tech.mmarca.openlink.OpenLinkSettings
import tech.mmarca.openwatch.devices.core.DailyGoal
import tech.mmarca.openwatch.devices.core.SharedGoals

/**
 * The settings OpenWatch shares with the apps connected over OpenLink (version 3): the daily
 * goals, each with the time it last changed, the newer value of a key winning
 * ([OpenLinkSettings.merge]). Kept as the library carries them, so a key OpenWatch itself has
 * no use for (a sleep goal) is still held and sent back to the app that has one.
 *
 * Read from memory, since the link answers on a binder thread. [changes] says what to publish:
 * every change, whether it came from a connected app or was made here. The daily goals are also
 * what a watch's sync sends and what its card sets ([SharedGoals]), in plain numbers.
 */
@Singleton
class LinkSettingsStore(
    private val prefs: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) : SharedGoals {

    @Inject
    constructor(@ApplicationContext context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private val state = MutableStateFlow(read())
    // The last one is replayed: a client may set settings before the app's start has begun
    // collecting, and the publish is its only answer.
    private val _changes = MutableSharedFlow<OpenLinkSettings>(replay = 1, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val settings: StateFlow<OpenLinkSettings> = state.asStateFlow()

    /** Every state after a change, for the link to publish. */
    val changes: SharedFlow<OpenLinkSettings> = _changes.asSharedFlow()

    /** What OpenLink's `settings()` answers. */
    fun linkSettings(): OpenLinkSettings = state.value

    /**
     * Takes what a connected app sent: the newer value per key. Publishes even when nothing
     * changed, since the publish is the app's answer. Returns whether anything changed.
     */
    @Synchronized
    fun accept(incoming: OpenLinkSettings): Boolean {
        val merged = held().merge(incoming)
        if (merged == state.value) {
            _changes.tryEmit(merged)
            return false
        }
        store(merged)
        return true
    }

    /** A change made here, stamped now, and later than what it replaces. [values] empty clears the key. */
    @Synchronized
    fun setLocal(key: OpenLinkSettingKey, values: List<Double>) {
        if (!key.accepts(values)) return
        val held = held()
        val before = held.entries[key.name]
        if (before?.values == values) return
        val at = maxOf(clock(), (before?.updatedAtMillis ?: 0L) + 1)
        store(held.with(key, values, at))
    }

    override fun current(): Map<DailyGoal, Int?> {
        val held = state.value
        return GOAL_KEYS.mapNotNull { (goal, key) ->
            val setting = held.entries[key.name] ?: return@mapNotNull null
            goal to setting.values.singleOrNull()?.roundToInt()
        }.toMap()
    }

    override fun set(goal: DailyGoal, value: Int?) {
        val key = GOAL_KEYS.getValue(goal)
        setLocal(key, listOfNotNull(value?.toDouble()))
    }

    /** What is held, checked as OpenLink checks it: a time this clock set ahead before it was put right pins nothing. */
    private fun held(): OpenLinkSettings = state.value.valid(clock())

    private fun store(next: OpenLinkSettings) {
        prefs.edit {
            for ((name, setting) in next.entries) {
                putString(name, "${setting.updatedAtMillis}$SEPARATOR${setting.values.joinToString(",")}")
            }
        }
        state.value = next
        _changes.tryEmit(next)
    }

    private fun read(): OpenLinkSettings = OpenLinkSettings(
        OpenLinkSettingKeys.ALL.keys.mapNotNull { name ->
            val raw = prefs.getString(name, null) ?: return@mapNotNull null
            val at = raw.substringBefore(SEPARATOR).toLongOrNull() ?: return@mapNotNull null
            val values = raw.substringAfter(SEPARATOR, "").split(',').filter { it.isNotEmpty() }
                .map { it.toDoubleOrNull() ?: return@mapNotNull null }
            name to OpenLinkSetting(values, at)
        }.toMap(),
    )

    private companion object {
        /** The goals a watch holds, by their OpenLink keys. */
        val GOAL_KEYS: Map<DailyGoal, OpenLinkSettingKey> = mapOf(
            DailyGoal.STEPS to OpenLinkSettingKeys.GOAL_STEPS,
            DailyGoal.ACTIVE_KCAL to OpenLinkSettingKeys.GOAL_ACTIVE_CALORIES_KCAL,
            DailyGoal.WATER_ML to OpenLinkSettingKeys.GOAL_WATER_ML,
        )

        const val PREFS = "openlink_settings"
        const val SEPARATOR = ':'
    }
}
