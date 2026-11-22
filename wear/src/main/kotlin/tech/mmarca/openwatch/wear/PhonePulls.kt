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
