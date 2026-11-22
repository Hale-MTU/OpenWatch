package tech.mmarca.openwatch.wear

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * When a phone last pulled from the watch, kept across restarts, and which
 * reminder the wearer was last shown for it ([SyncReminder]). Plain
 * preferences, in a file of their own, so the settings' listeners do not run
 * at every pull.
 *
 * A pull is counted when the phone asks for the workouts (`WO_SINCE`), the
 * last series of a phone sync that went through every series, every page of
 * it: the request handler's other series are paged by the link host, which
 * has no hook for it. Several phones may pull; the newest pull counts.
 */
class PhonePulls(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    /** When a phone last pulled, or null when none ever did. */
    val lastPulledMillis: Long?
        get() = prefs.getLong(KEY_LAST_PULLED, 0L).takeIf { it > 0 }

    /** A phone pulled at [epochMillis]. Any thread. */
    fun notePulled(epochMillis: Long) {
        prefs.edit { putLong(KEY_LAST_PULLED, epochMillis) }
    }

    /** The reminder last shown, as "<reference millis> <level>", or null for none. */
    var reminded: String?
        get() = prefs.getString(KEY_REMINDED, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_REMINDED) else putString(KEY_REMINDED, value) }

    companion object {
        /** Final once shipped: the file and its keys are read by every later build. */
        const val PREFS = "wear_phone_pulls"
        const val KEY_LAST_PULLED = "last_pulled_millis"
        const val KEY_REMINDED = "reminded"
    }
}

/**
 * How close the watch is to dropping rows the phone has not pulled. Every
 * series is kept a week ([WearMetric.WEEK_MILLIS]) and pruned by age, pulled
 * or not; so the rows recorded just after the last pull are the first to go,
 * a week after it. The wearer is warned from [WARN_AFTER_MILLIS], two days
 * before. Pure.
 */
internal object SyncRetention {

    enum class Level { SOON, PRUNING }

    /** A warning: its level, the pull it counts from, and when the rows recorded after it start to go. */
    data class Warning(val level: Level, val referenceMillis: Long, val pruneFromMillis: Long) {
        /** Whole days left before the pruning reaches unpulled rows, at least one while it has not. */
        fun daysLeft(nowMillis: Long): Int =
            ((pruneFromMillis - nowMillis + DAY_MILLIS - 1) / DAY_MILLIS).toInt().coerceAtLeast(1)

        /** What [SyncReminder] stores, so a reminder is shown once per pull and level. */
        val key: String get() = "$referenceMillis ${level.name}"
    }

    const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000

    /** Five days without a pull: two days before the pruning reaches what was not pulled. */
    const val WARN_AFTER_MILLIS: Long = 5 * DAY_MILLIS

    /**
     * The warning as of [nowMillis], or null when there is none. It counts
     * from the last pull, or, for a watch no phone ever pulled from, from the
     * first phone the wearer allowed; with neither there is no phone to sync
     * with, and no warning.
     */
    fun warning(nowMillis: Long, lastPulledMillis: Long?, firstTrustedMillis: Long?): Warning? {
        val reference = lastPulledMillis ?: firstTrustedMillis ?: return null
        val pruneFrom = reference + WearMetric.WEEK_MILLIS
        val level = when {
            nowMillis >= pruneFrom -> Level.PRUNING
            nowMillis >= reference + WARN_AFTER_MILLIS -> Level.SOON
            else -> return null
        }
        return Warning(level, reference, pruneFrom)
    }

    /** [warning], unless the reminder for its pull and level was shown already ([reminded] is [Warning.key] then). */
    fun reminderToShow(warning: Warning, reminded: String?): Warning? = warning.takeIf { it.key != reminded }
}
