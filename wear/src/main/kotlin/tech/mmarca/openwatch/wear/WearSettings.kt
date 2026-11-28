package tech.mmarca.openwatch.wear

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.mmarca.healthcalculations.preferences.BiologicalSex
import tech.mmarca.openwatch.wearlink.HeartRateInterval

/**
 * The choices made on the watch's status screen, kept on the watch.
 *
 * - [heartRateDay]: how often the heart rate is measured outside bedtime
 *   mode, every 10 seconds (the default, as in OpenVitals) or power save.
 *   Bedtime mode always measures at the full rate, because the sleep
 *   pipeline reads every sample (`HeartRateSchedule`).
 * - [heartRateSource] and [hrvSource]: the raw Android sensors by default.
 *   The Samsung Health Platform is offered only on a watch that has it, and
 *   only by choice, because outside the platform's Developer Mode the
 *   platform asks Samsung about this app before it answers, and the app
 *   cannot tell which mode is on.
 * - [unitSystem]: how the screens and tiles show a reading, chosen in the
 *   watch's settings; metric until the wearer chooses.
 * - `auto_pause` and `auto_detect` ([WatchPreferences.autoPause],
 *   [WatchPreferences.autoDetect]): a workout's pause by itself and the
 *   offer of a walk or run seen in the step minutes, or of a ride seen in the
 *   heart rate (with the phone's maximum), both on by default.
 */
internal object WearSettings {

    /** Defined in `:wear:recording-core`, which [RecorderPlan] and [WatchPreferences] live in. */
    typealias Source = MeasurementSource

    private const val PREFS = "wear_settings"
    private const val KEY_HEART_RATE_DAY = "heart_rate_day"
    private const val KEY_HEART_RATE_SOURCE = "heart_rate_source"
    private const val KEY_HRV_SOURCE = "hrv_source"
    /** Earlier builds' key: the hold in force, written with every other setting. Read, and written with a choice. */
    private const val KEY_SYNCED_SLEEP_HOLD = "synced_sleep_hold"

    /** Written only when the wearer or the phone chooses: [CHOICE_SYNCED] or [CHOICE_LOCK]. */
    private const val KEY_SLEEP_HOLD_CHOICE = "sleep_hold_choice"
    private const val CHOICE_SYNCED = "synced"
    private const val CHOICE_LOCK = "lock"
    private const val KEY_SKIN_TEMPERATURE = "skin_temperature"
    private const val KEY_SPO2 = "spo2_at_night"
    private const val KEY_MAX_HEART_RATE = "max_heart_rate"
    private const val KEY_RESTING_HEART_RATE = "resting_heart_rate"

    /** The body profile the phone sent: the weight in 100 g, the height in cm, the birth year, `0` for none; the sex `f` or `m`, absent for none. */
    private const val KEY_WEIGHT_HG = "body_weight_hg"
    private const val KEY_HEIGHT_CM = "body_height_cm"
    private const val KEY_BIRTH_YEAR = "birth_year"
    private const val KEY_SEX = "sex"
    private const val SEX_FEMALE = "f"
    private const val SEX_MALE = "m"

    /** A [UnitSystem] name; written only when the wearer chooses. */
    private const val KEY_UNIT_SYSTEM = "unit_system"

    /** Whether a workout pauses by itself, and whether a walk, run or ride is offered as one; both on until the wearer switches them off. */
    private const val KEY_AUTO_PAUSE = "auto_pause"
    private const val KEY_AUTO_DETECT = "auto_detect"
    private const val KEY_GOAL_STEPS = "goal_steps"
    private const val KEY_GOAL_KCAL = "goal_kcal"
    private const val KEY_GOAL_WATER = "goal_water"

    fun heartRateDay(context: Context): HeartRateInterval =
        prefs(context).getString(KEY_HEART_RATE_DAY, null)?.let(HeartRateInterval::fromWire)
            ?.takeIf { it != HeartRateInterval.OFF } ?: HeartRateInterval.EVERY_10_SECONDS

    fun setHeartRateDay(context: Context, interval: HeartRateInterval) {
        prefs(context).edit().putString(KEY_HEART_RATE_DAY, interval.wire).apply()
    }

    fun heartRateSource(context: Context): Source = source(context, KEY_HEART_RATE_SOURCE)

    fun setHeartRateSource(context: Context, source: Source) = setSource(context, KEY_HEART_RATE_SOURCE, source)

    fun hrvSource(context: Context): Source = source(context, KEY_HRV_SOURCE)

    fun setHrvSource(context: Context, source: Source) = setSource(context, KEY_HRV_SOURCE, source)

    /**
     * How a sleep hold keeps every minute whole: true with the heart rate's
     * wake-ups and a flush of every sensor at each ([SensorWakeSync]), which
     * lets the processor sleep in between; false with a lasting wake lock.
     * It falls back to the lock while the heart rate is not dense from the
     * sensor. The wearer's or the phone's choice holds; without one, the
     * synced hold wherever the heart rate sensor wakes the processor
     * ([syncedSleepHoldOf]).
     */
    fun syncedSleepHold(context: Context): Boolean {
        val prefs = prefs(context)
        return syncedSleepHoldOf(
            choice = prefs.getString(KEY_SLEEP_HOLD_CHOICE, null),
            legacySynced = prefs.getBoolean(KEY_SYNCED_SLEEP_HOLD, false),
            wakeUpHeartRate = hasWakeUpHeartRate(context),
        )
    }

    /** The wearer or the phone chose how a sleep hold works: the choice is kept whatever the default becomes. */
    fun setSyncedSleepHold(context: Context, synced: Boolean) {
        prefs(context).edit()
            .putString(KEY_SLEEP_HOLD_CHOICE, if (synced) CHOICE_SYNCED else CHOICE_LOCK)
            // What an older build reads, should the watch go back to one.
            .putBoolean(KEY_SYNCED_SLEEP_HOLD, synced)
            .apply()
    }

    /**
     * The sleep hold in force. A [choice] of the wearer or the phone wins.
     * Without one, a `true` under the key of earlier builds is a choice too
     * (their default was the lock, so only a choice wrote it), while a
     * `false` there says nothing: those builds wrote every setting each time
     * the phone changed one, so it is on nearly every watch. Then the synced
     * hold where the heart rate sensor is a wake-up sensor, the lock where it
     * is not. Pure.
     */
    internal fun syncedSleepHoldOf(choice: String?, legacySynced: Boolean, wakeUpHeartRate: Boolean): Boolean = when (choice) {
        CHOICE_SYNCED -> true
        CHOICE_LOCK -> false
        else -> legacySynced || wakeUpHeartRate
    }

    /** Whether the watch's heart rate sensor has a wake-up variant, the one [SensorHeartRateSource] registers. */
    private fun hasWakeUpHeartRate(context: Context): Boolean =
        (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.getDefaultSensor(Sensor.TYPE_HEART_RATE, true) != null

    /** Skin temperature at night: the Samsung platform or, on Pixel, the raw sensor. Off by default. */
    fun skinTemperature(context: Context): Boolean = prefs(context).getBoolean(KEY_SKIN_TEMPERATURE, false)

    fun setSkinTemperature(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_SKIN_TEMPERATURE, on).apply()
    }

    /** SpO2 spot measurements at night from the Samsung platform, the only source. Off by default. */
    fun spo2AtNight(context: Context): Boolean = prefs(context).getBoolean(KEY_SPO2, false)

    fun setSpo2AtNight(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPO2, on).apply()
    }

    /** The maximum heart rate the phone sent for the zones; null for none. */
    fun maxHeartRate(context: Context): Int? = prefs(context).getInt(KEY_MAX_HEART_RATE, 0).takeIf { it > 0 }

    /** The resting heart rate the phone sent for the zones; null for none. */
    fun restingHeartRate(context: Context): Int? = prefs(context).getInt(KEY_RESTING_HEART_RATE, 0).takeIf { it > 0 }

    /** The wearer's unit system; metric until one is chosen, or for a name this build does not know. */
    fun unitSystem(context: Context): UnitSystem {
        val stored = prefs(context).getString(KEY_UNIT_SYSTEM, null)
        return UnitSystem.entries.firstOrNull { it.name == stored } ?: UnitSystem.METRIC
    }

    fun setUnitSystem(context: Context, unitSystem: UnitSystem) {
        prefs(context).edit { putString(KEY_UNIT_SYSTEM, unitSystem.name) }
    }

    /** Every setting in one value, for the phone's `GET_PREF` and the screens. */
    fun read(context: Context): WatchPreferences = WatchPreferences(
        heartRateDay = heartRateDay(context),
        heartRateSource = heartRateSource(context),
        hrvSource = hrvSource(context),
        syncedSleepHold = syncedSleepHold(context),
        skinTemperature = skinTemperature(context),
        spo2AtNight = spo2AtNight(context),
        maxHeartRate = maxHeartRate(context),
        restingHeartRate = restingHeartRate(context),
        unitSystem = unitSystem(context),
        weightHectograms = positive(context, KEY_WEIGHT_HG),
        heightCm = positive(context, KEY_HEIGHT_CM),
        birthYear = positive(context, KEY_BIRTH_YEAR),
        sex = when (prefs(context).getString(KEY_SEX, null)) {
            SEX_FEMALE -> BiologicalSex.FEMALE
            SEX_MALE -> BiologicalSex.MALE
            else -> null
        },
        autoPause = prefs(context).getBoolean(KEY_AUTO_PAUSE, true),
        autoDetect = prefs(context).getBoolean(KEY_AUTO_DETECT, true),
        stepGoal = goal(context, KEY_GOAL_STEPS, WatchPreferences.DEFAULT_STEP_GOAL),
        activeKcalGoal = goal(context, KEY_GOAL_KCAL, WatchPreferences.DEFAULT_ACTIVE_KCAL_GOAL),
        waterGoalMl = goal(context, KEY_GOAL_WATER, WatchPreferences.DEFAULT_WATER_GOAL_ML) ?: WatchPreferences.DEFAULT_WATER_GOAL_ML,
    )

    /**
     * Stores every setting of [preferences], for the phone's `SET_PREF`. The
     * phone changes one setting at a time and this writes them all, so the
     * sleep hold is recorded as a choice only when it changed.
     */
    fun write(context: Context, preferences: WatchPreferences) {
        setHeartRateDay(context, preferences.heartRateDay)
        setHeartRateSource(context, preferences.heartRateSource)
        setHrvSource(context, preferences.hrvSource)
        if (preferences.syncedSleepHold != syncedSleepHold(context)) setSyncedSleepHold(context, preferences.syncedSleepHold)
        setSkinTemperature(context, preferences.skinTemperature)
        setSpo2AtNight(context, preferences.spo2AtNight)
        prefs(context).edit()
            .putInt(KEY_MAX_HEART_RATE, preferences.maxHeartRate ?: 0)
            .putInt(KEY_RESTING_HEART_RATE, preferences.restingHeartRate ?: 0)
            .putInt(KEY_WEIGHT_HG, preferences.weightHectograms ?: 0)
            .putInt(KEY_HEIGHT_CM, preferences.heightCm ?: 0)
            .putInt(KEY_BIRTH_YEAR, preferences.birthYear ?: 0)
            .putBoolean(KEY_AUTO_PAUSE, preferences.autoPause)
            .putBoolean(KEY_AUTO_DETECT, preferences.autoDetect)
            .putInt(KEY_GOAL_STEPS, preferences.stepGoal ?: 0)
            .putInt(KEY_GOAL_KCAL, preferences.activeKcalGoal ?: 0)
            .putInt(KEY_GOAL_WATER, preferences.waterGoalMl)
            .apply {
                when (preferences.sex) {
                    BiologicalSex.FEMALE -> putString(KEY_SEX, SEX_FEMALE)
                    BiologicalSex.MALE -> putString(KEY_SEX, SEX_MALE)
                    null -> remove(KEY_SEX)
                }
            }
            .apply()
        // Like the sleep hold, kept only once chosen: a write of the other settings leaves it unset.
        if (preferences.unitSystem != unitSystem(context)) setUnitSystem(context, preferences.unitSystem)
    }

    private fun positive(context: Context, key: String): Int? = prefs(context).getInt(key, 0).takeIf { it > 0 }

    /** A stored goal, [default] before the phone set one, null for a stored `0` (no goal). */
    private fun goal(context: Context, key: String, default: Int): Int? {
        val prefs = prefs(context)
        return if (prefs.contains(key)) prefs.getInt(key, 0).takeIf { it > 0 } else default
    }

    private fun source(context: Context, key: String): Source {
        val stored = prefs(context).getString(key, null)
        return Source.entries.firstOrNull { it.name == stored } ?: Source.RAW_SENSOR
    }

    private fun setSource(context: Context, key: String, source: Source) {
        prefs(context).edit().putString(key, source.name).apply()
    }

    internal fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Every setting as one [StateFlow] that follows each write: the wearer's on
 * the status screen and the phone's `SET_PREF` alike, since both end in
 * [WearSettings]' preferences file. One per process ([WearGraph.settings]);
 * build it off the main thread, since the first read loads the file.
 */
internal class WearSettingsState(private val context: Context) {

    private val flow = MutableStateFlow(WearSettings.read(context))

    val state: StateFlow<WatchPreferences> = flow.asStateFlow()

    // SharedPreferences keeps its listeners weakly: this field holds this one. Called on the main thread.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> flow.value = WearSettings.read(context) }

    init {
        WearSettings.prefs(context).registerOnSharedPreferenceChangeListener(listener)
        // A write between the first read and the registration is not missed.
        flow.value = WearSettings.read(context)
    }
}
