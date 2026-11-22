package tech.mmarca.openwatch.devices.wearos

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.data.repository.DeviceStateStore
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * How far each Wear OS watch has been pulled, per device id: the time of the
 * newest heart rate sample, step minute and floor minute pulled, and the
 * time of the newest sleep minute an estimate was made from. The next sync asks the
 * watch for what is newer. A re-pull is harmless: the imports' record ids
 * are deterministic, so an upsert follows.
 *
 * Kept in `device_state`, so the cursors go with their device. Until 2026-11 they were in
 * `wearos_sync_cursors`, adopted once for the devices still registered.
 */
@Singleton
class WearOsSyncCursorStore(private val state: DeviceStateStore) {

    @Inject
    constructor(state: DeviceStateStore, registry: BleDeviceRepository, @ApplicationContext context: Context) : this(state) {
        adoptLegacy(context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE), registry.devices.mapTo(HashSet()) { it.id })
    }

    /** The cursor, or [Instant.EPOCH] for a watch never synced. */
    fun heartRateCursor(deviceId: String): Instant = cursor(deviceId, HR)

    fun setHeartRateCursor(deviceId: String, at: Instant) = setCursor(deviceId, HR, at)

    /** The sleep cursor, or [Instant.EPOCH] for a watch never synced. */
    fun sleepCursor(deviceId: String): Instant = cursor(deviceId, SLEEP)

    fun setSleepCursor(deviceId: String, at: Instant) = setCursor(deviceId, SLEEP, at)

    /** The step cursor, or [Instant.EPOCH] for a watch never synced. */
    fun stepsCursor(deviceId: String): Instant = cursor(deviceId, STEPS)

    fun setStepsCursor(deviceId: String, at: Instant) = setCursor(deviceId, STEPS, at)

    /** The floor cursor, or [Instant.EPOCH] for a watch never synced. */
    fun ascentCursor(deviceId: String): Instant = cursor(deviceId, ASCENT)

    fun setAscentCursor(deviceId: String, at: Instant) = setCursor(deviceId, ASCENT, at)

    /** The HRV cursor: the start of the newest window written. [Instant.EPOCH] for a watch never synced. */
    fun hrvCursor(deviceId: String): Instant = cursor(deviceId, HRV)

    fun setHrvCursor(deviceId: String, at: Instant) = setCursor(deviceId, HRV, at)

    /** The breathing rate cursor: the start of the newest window written. [Instant.EPOCH] for a watch never synced. */
    fun respiratoryCursor(deviceId: String): Instant = cursor(deviceId, RESPIRATORY)

    fun setRespiratoryCursor(deviceId: String, at: Instant) = setCursor(deviceId, RESPIRATORY, at)

    /** The SpO2 cursor: the time of the newest measurement written. [Instant.EPOCH] for a watch never synced. */
    fun spo2Cursor(deviceId: String): Instant = cursor(deviceId, SPO2)

    fun setSpo2Cursor(deviceId: String, at: Instant) = setCursor(deviceId, SPO2, at)

    /** The skin temperature cursor: the end of the newest night run written. [Instant.EPOCH] for a watch never synced. */
    fun skinCursor(deviceId: String): Instant = cursor(deviceId, SKIN)

    fun setSkinCursor(deviceId: String, at: Instant) = setCursor(deviceId, SKIN, at)

    /** The medians of the nights written so far, oldest first: the skin temperature baseline's memory. */
    fun skinNightMedians(deviceId: String): List<Double> =
        state.get(deviceId, SKIN_MEDIANS).orEmpty().split(',').mapNotNull { it.toDoubleOrNull() }

    fun setSkinNightMedians(deviceId: String, medians: List<Double>) {
        state.put(deviceId, SKIN_MEDIANS, medians.joinToString(","))
    }

    /** The workout cursor: the start of the newest workout written. [Instant.EPOCH] for a watch never synced. */
    fun workoutCursor(deviceId: String): Instant = cursor(deviceId, WORKOUT)

    fun setWorkoutCursor(deviceId: String, at: Instant) = setCursor(deviceId, WORKOUT, at)

    fun clear(deviceId: String) {
        Keys.forEach { state.put(deviceId, it, null) }
    }

    private fun cursor(deviceId: String, key: String): Instant =
        Instant.ofEpochMilli(state.get(deviceId, key)?.toLongOrNull() ?: 0L)

    private fun setCursor(deviceId: String, key: String, at: Instant) {
        state.put(deviceId, key, at.toEpochMilli().toString())
    }

    /** The old file's cursors (`hr_cursor_<id>` and the rest), once, then marked as taken over. */
    internal fun adoptLegacy(legacy: SharedPreferences, registered: Set<String>) {
        if (legacy.getBoolean(KEY_ADOPTED, false)) return
        val byDevice = mutableMapOf<String, MutableMap<String, String>>()
        for ((name, value) in legacy.all) {
            val key = Keys.firstOrNull { name.startsWith("${it}_") } ?: continue
            byDevice.getOrPut(name.removePrefix("${key}_")) { mutableMapOf() }[key] = value.toString()
        }
        state.adopt(byDevice, registered)
        legacy.edit(commit = true) { putBoolean(KEY_ADOPTED, true) }
    }

    private companion object {
        const val PREFS_FILE = "wearos_sync_cursors"
        const val KEY_ADOPTED = "adopted_into_device_state"
        const val HR = "hr_cursor"
        const val SLEEP = "sleep_cursor"
        const val STEPS = "steps_cursor"
        const val ASCENT = "ascent_cursor"
        const val HRV = "hrv_cursor"
        const val RESPIRATORY = "respiratory_cursor"
        const val SPO2 = "spo2_cursor"
        const val SKIN = "skin_cursor"
        const val WORKOUT = "workout_cursor"
        const val SKIN_MEDIANS = "skin_medians"
        val Keys = listOf(HR, SLEEP, STEPS, ASCENT, HRV, RESPIRATORY, SPO2, SKIN, WORKOUT, SKIN_MEDIANS)
    }
}
