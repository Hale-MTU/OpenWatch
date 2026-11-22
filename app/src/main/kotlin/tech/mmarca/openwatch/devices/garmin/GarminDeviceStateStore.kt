package tech.mmarca.openwatch.devices.garmin

import android.content.Context
import android.content.SharedPreferences
import tech.mmarca.openwatch.data.repository.DeviceStateStore
import androidx.core.content.edit
import java.time.DayOfWeek
import org.json.JSONArray
import tech.mmarca.openwatch.devices.core.sync.AutoSyncInterval
import tech.mmarca.openwatch.devices.core.sync.AutoSyncIntervalStore

/**
 * Garmin's per-device state: the capability bitmap from the last handshake, which files a
 * previous sync pulled, the watch's switches and alarms, and every watch's sync interval. Kept
 * in `device_state` ([state]), so it goes with its device; lists are JSON arrays to keep order.
 * Until 2026-11 it was in `garmin_device_state` under the Flutter build's key names, adopted
 * once for the devices still registered. The one flag a link opener needs by address alone,
 * the large MTU, stays in that file ([linkPrefs]).
 */
class GarminDeviceStateStore(
    private val state: DeviceStateStore,
    private val linkPrefs: SharedPreferences,
) : AutoSyncIntervalStore {

    constructor(context: Context, state: DeviceStateStore, registered: Set<String>) : this(
        state = state,
        linkPrefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE),
    ) {
        adoptLegacy(linkPrefs, registered)
    }

    /** What the watch declared in its last handshake. Empty if never synced. */
    fun capabilities(deviceId: String): Set<GarminCapability> {
        val raw = readList(deviceId, KEY_CAPABILITIES) ?: return emptySet()
        // Matched by wire name, not index, so naming a flag cannot rot stored data.
        val byName = GarminCapability.entries.associateBy { it.wireName }
        return raw.mapNotNull { byName[it] }.toSet()
    }

    fun recordCapabilities(deviceId: String, capabilities: Set<GarminCapability>) {
        if (capabilities.isEmpty()) return
        writeList(deviceId, KEY_CAPABILITIES,
            capabilities.map { it.wireName },
        )
    }

    /** Files a previous sync pulled, by `GarminDirectoryEntry.dedupKey`. */
    fun syncedFileKeys(deviceId: String): Set<String> =
        readList(deviceId, KEY_SYNCED_KEYS)?.toSet() ?: emptySet()

    fun recordSyncedFileKeys(deviceId: String, keys: Iterable<String>) {
        if (!keys.any()) return
        // A list keeps insertion order, so trimming from the front drops the oldest keys.
        val existing = readList(deviceId, KEY_SYNCED_KEYS).orEmpty()
        val existingSet = existing.toHashSet()
        val merged = existing + keys.filterNot { it in existingSet }
        val trimmed = if (merged.size > MAX_SYNCED_FILE_KEYS) {
            merged.subList(merged.size - MAX_SYNCED_FILE_KEYS, merged.size)
        } else {
            merged
        }
        writeList(deviceId, KEY_SYNCED_KEYS, trimmed)
    }

    fun clearSyncedFileKeys(deviceId: String) {
        state.put(deviceId, KEY_SYNCED_KEYS, null)
    }

    /**
     * Whether the watch still needs the pair-flow completion trio. Set on
     * onboarding, cleared once a session has sent it.
     */
    fun setupWizardPending(deviceId: String): Boolean =
        state.get(deviceId, KEY_SETUP_WIZARD)?.toBooleanStrictOrNull() ?: false

    fun setSetupWizardPending(deviceId: String, pending: Boolean) {
        state.put(deviceId, KEY_SETUP_WIZARD, pending.toString())
    }

    /** Whether the watch streams live readings over the held link. Off by default: it costs battery. */
    fun liveReadings(deviceId: String): Boolean =
        state.get(deviceId, KEY_LIVE_READINGS)?.toBooleanStrictOrNull() ?: false

    fun setLiveReadings(deviceId: String, enabled: Boolean) {
        state.put(deviceId, KEY_LIVE_READINGS, enabled.toString())
    }

    /** Whether the watch may read the phone's calendar. Off by default. */
    fun calendarSync(deviceId: String): Boolean =
        state.get(deviceId, KEY_CALENDAR_SYNC)?.toBooleanStrictOrNull() ?: false

    fun setCalendarSync(deviceId: String, enabled: Boolean) {
        state.put(deviceId, KEY_CALENDAR_SYNC, enabled.toString())
    }

    /** Whether CoMaps guidance is shown on the watch. Off by default. */
    fun navigationOnWatch(deviceId: String): Boolean =
        state.get(deviceId, KEY_NAVIGATION_ON_WATCH)?.toBooleanStrictOrNull() ?: false

    fun setNavigationOnWatch(deviceId: String, enabled: Boolean) {
        state.put(deviceId, KEY_NAVIGATION_ON_WATCH, enabled.toString())
    }

    /** Whether the watch's music controls drive the phone's player. Off by default. */
    fun musicControls(deviceId: String): Boolean =
        state.get(deviceId, KEY_MUSIC_CONTROLS)?.toBooleanStrictOrNull() ?: false

    fun setMusicControls(deviceId: String, enabled: Boolean) {
        state.put(deviceId, KEY_MUSIC_CONTROLS, enabled.toString())
    }

    /**
     * Whether the link is held open whenever the watch is in range. On by
     * default: weather, find-my-phone, live readings and guidance all ride it.
     * A wearer who turns it off has that written down.
     */
    fun stayConnected(deviceId: String): Boolean =
        state.get(deviceId, KEY_STAY_CONNECTED)?.toBooleanStrictOrNull() ?: true

    fun setStayConnected(deviceId: String, enabled: Boolean) {
        state.put(deviceId, KEY_STAY_CONNECTED, enabled.toString())
    }

    /**
     * How often the watch syncs on its own. Off by default. Stored as
     * minutes, so a dropped interval degrades to off, not another schedule.
     */
    // Every watch's interval, Wear OS included, under its historical key.
    override fun autoSyncInterval(deviceId: String): AutoSyncInterval =
        AutoSyncInterval.fromMinutes(state.get(deviceId, KEY_AUTO_SYNC)?.toIntOrNull() ?: 0)

    override fun setAutoSyncInterval(deviceId: String, interval: AutoSyncInterval) {
        state.put(deviceId, KEY_AUTO_SYNC, interval.minutes.toString())
    }

    /** The listing protocol a previous sync proved for this watch. */
    fun syncProtocol(deviceId: String): GarminSyncProtocol {
        val stored = state.get(deviceId, KEY_SYNC_PROTOCOL)
        return GarminSyncProtocol.entries.firstOrNull { it.name == stored }
            ?: GarminSyncProtocol.UNKNOWN
    }

    fun recordSyncProtocol(deviceId: String, protocol: GarminSyncProtocol) {
        if (protocol == GarminSyncProtocol.UNKNOWN) return
        state.put(deviceId, KEY_SYNC_PROTOCOL, protocol.name)
    }

    /** The alarms set here for a watch with no settings tree. The watch's own are never read. */
    fun alarms(deviceId: String): List<GarminAlarm> =
        readList(deviceId, KEY_ALARMS).orEmpty().mapNotNull(::decodeAlarm)

    fun setAlarms(deviceId: String, alarms: List<GarminAlarm>) {
        writeList(deviceId, KEY_ALARMS, alarms.map(::encodeAlarm))
    }

    /** The list the watch last accepted, or null when none was sent. */
    fun sentAlarms(deviceId: String): List<GarminAlarm>? =
        readList(deviceId, KEY_SENT_ALARMS)?.mapNotNull(::decodeAlarm)

    fun recordSentAlarms(deviceId: String, alarms: List<GarminAlarm>) {
        writeList(deviceId, KEY_SENT_ALARMS, alarms.map(::encodeAlarm))
    }

    /**
     * Whether links to the watch at [address] ask for a large MTU. Default on.
     * Keyed by Bluetooth address rather than device id:
     * every link opener knows the address, and not all know the device id.
     */
    fun highMtu(address: String): Boolean =
        linkPrefs.getBoolean(highMtuPrefsKey(address), true)

    fun setHighMtu(address: String, enabled: Boolean) {
        linkPrefs.edit { putBoolean(highMtuPrefsKey(address), enabled) }
    }

    /** Everything kept for the device: by id, and the link's own flag by its [address]. */
    fun clear(deviceId: String, address: String? = null) {
        state.forget(deviceId)
        address?.let { linkPrefs.edit { remove(highMtuPrefsKey(it)) } }
    }

    private fun readList(deviceId: String, key: String): List<String>? {
        val raw = state.get(deviceId, key) ?: return null
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { array.getString(it) }
        }.getOrNull()
    }

    private fun writeList(deviceId: String, key: String, values: List<String>) {
        state.put(deviceId, key, JSONArray(values).toString())
    }

    /**
     * The old file's per-device values (`garmin_capabilities_<id>` and the rest), once, then
     * marked as taken over. The longest prefix is matched first: one is another's beginning.
     */
    internal fun adoptLegacy(legacy: SharedPreferences, registered: Set<String>) {
        if (legacy.getBoolean(KEY_ADOPTED, false)) return
        val prefixes = Keys.sortedByDescending { it.length }
        val byDevice = mutableMapOf<String, MutableMap<String, String>>()
        for ((name, value) in legacy.all) {
            val key = prefixes.firstOrNull { name.startsWith("${it}_") } ?: continue
            byDevice.getOrPut(name.removePrefix("${key}_")) { mutableMapOf() }[key] = value.toString()
        }
        state.adopt(byDevice, registered)
        legacy.edit(commit = true) { putBoolean(KEY_ADOPTED, true) }
    }

    private fun highMtuPrefsKey(address: String) = "garmin_high_mtu_${address.uppercase()}"

    companion object {
        const val PREFS_FILE = "garmin_device_state"
        private const val KEY_ADOPTED = "adopted_into_device_state"
        private const val KEY_SYNCED_KEYS = "ble_synced_files"
        private const val KEY_CAPABILITIES = "garmin_capabilities"
        private const val KEY_STAY_CONNECTED = "garmin_stay_connected"
        private const val KEY_SETUP_WIZARD = "garmin_setup_wizard_pending"
        private const val KEY_LIVE_READINGS = "garmin_live_readings"
        private const val KEY_CALENDAR_SYNC = "garmin_calendar_sync"
        private const val KEY_NAVIGATION_ON_WATCH = "garmin_navigation_on_watch"
        private const val KEY_MUSIC_CONTROLS = "garmin_music_controls"
        private const val KEY_AUTO_SYNC = "garmin_auto_sync_minutes"
        private const val KEY_SYNC_PROTOCOL = "garmin_sync_protocol"
        private const val KEY_ALARMS = "garmin_alarms"
        private const val KEY_SENT_ALARMS = "garmin_alarms_sent"
        private val Keys = listOf(KEY_SYNCED_KEYS, KEY_CAPABILITIES, KEY_STAY_CONNECTED, KEY_SETUP_WIZARD, KEY_LIVE_READINGS, KEY_CALENDAR_SYNC, KEY_NAVIGATION_ON_WATCH, KEY_MUSIC_CONTROLS, KEY_AUTO_SYNC, KEY_SYNC_PROTOCOL, KEY_ALARMS, KEY_SENT_ALARMS)

        /** Cap on remembered file keys per watch, so the list cannot grow without bound. */
        private const val MAX_SYNCED_FILE_KEYS = 4000
    }
}

/** One alarm as `minuteOfDay,repeatMask,enabled,sound,backlight,label`. Enums go by name. */
private fun encodeAlarm(alarm: GarminAlarm): String = listOf(
    alarm.minuteOfDay,
    alarm.repeatMask,
    alarm.enabled,
    alarm.sound.name,
    alarm.backlight,
    alarm.label.name,
).joinToString(",")

/** Null for a line this build cannot read, so one bad entry does not lose the rest. */
private fun decodeAlarm(line: String): GarminAlarm? {
    val parts = line.split(",")
    if (parts.size != 6) return null
    val minuteOfDay = parts[0].toIntOrNull()?.takeIf { it in 0 until 24 * 60 } ?: return null
    val mask = parts[1].toLongOrNull() ?: return null
    return GarminAlarm(
        hour = minuteOfDay / 60,
        minute = minuteOfDay % 60,
        days = DayOfWeek.entries.filter { mask and (1L shl (it.value - 1)) != 0L }.toSet(),
        enabled = parts[2].toBoolean(),
        sound = GarminAlarmSound.entries.firstOrNull { it.name == parts[3] } ?: return null,
        backlight = parts[4].toBoolean(),
        label = GarminAlarmLabel.entries.firstOrNull { it.name == parts[5] } ?: return null,
    )
}
