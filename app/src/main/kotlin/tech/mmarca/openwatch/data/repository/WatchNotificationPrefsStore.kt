package tech.mmarca.openwatch.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which phone notifications are mirrored to a watch: the one copy of the setting, read by the
 * settings screen, the listener and the bridge alike. The disclosure flag is stored so toggling
 * does not re-prompt.
 *
 * Key names are Flutter's. Values the migrator left in the main file are adopted once, and so
 * is the listener's own copy (`openwatch_notification_listener`), which a screen kept in step
 * until 2026-11 and which is then dropped.
 */
@Singleton
class WatchNotificationPrefsStore(
    private val prefs: SharedPreferences,
    legacyMainPrefs: SharedPreferences? = null,
    listenerPrefs: SharedPreferences? = null,
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE),
        listenerPrefs = context.getSharedPreferences(LISTENER_PREFS_FILE, Context.MODE_PRIVATE),
    )

    init {
        if (legacyMainPrefs != null) adoptMigratedValues(legacyMainPrefs)
        if (listenerPrefs != null) adoptListenerCopy(listenerPrefs)
    }

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** Packages the user has silenced. */
    var blockedPackages: Set<String>
        get() = prefs.getStringSet(KEY_BLOCKED, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit { putStringSet(KEY_BLOCKED, value) }

    fun setBlocked(packageName: String, blocked: Boolean) {
        val next = blockedPackages.toMutableSet()
        if (blocked) next.add(packageName) else next.remove(packageName)
        blockedPackages = next
    }

    /** Whether the user accepted the disclosure. Required by Google Play before the request. */
    var disclosureAccepted: Boolean
        get() = prefs.getBoolean(KEY_DISCLOSURE_ACCEPTED, false)
        set(value) = prefs.edit { putBoolean(KEY_DISCLOSURE_ACCEPTED, value) }

    /** One-time adoption of values the migrator left in the main file. Own-file keys win. */
    private fun adoptMigratedValues(main: SharedPreferences) {
        prefs.edit {
            if (!prefs.contains(KEY_ENABLED) && main.contains(KEY_ENABLED)) {
                putBoolean(KEY_ENABLED, main.getBoolean(KEY_ENABLED, false))
            }
            if (!prefs.contains(KEY_BLOCKED) && main.contains(KEY_BLOCKED)) {
                putStringSet(KEY_BLOCKED, main.getStringSet(KEY_BLOCKED, emptySet()))
            }
            if (!prefs.contains(KEY_DISCLOSURE_ACCEPTED) &&
                main.contains(KEY_DISCLOSURE_ACCEPTED)
            ) {
                putBoolean(
                    KEY_DISCLOSURE_ACCEPTED,
                    main.getBoolean(KEY_DISCLOSURE_ACCEPTED, false),
                )
            }
        }
    }

    /**
     * The listener's copy, adopted where this store has no value of its own (a Flutter install
     * whose screen never ran), then removed so it cannot drift again. Committed before the
     * removal, so a process that dies between the two loses nothing.
     */
    private fun adoptListenerCopy(listener: SharedPreferences) {
        if (ListenerKeys.none(listener::contains)) return
        prefs.edit(commit = true) {
            if (!prefs.contains(KEY_ENABLED) && listener.contains(LISTENER_KEY_ENABLED)) {
                putBoolean(KEY_ENABLED, listener.getBoolean(LISTENER_KEY_ENABLED, false))
            }
            if (!prefs.contains(KEY_BLOCKED) && listener.contains(LISTENER_KEY_BLOCKED)) {
                putStringSet(KEY_BLOCKED, listener.getStringSet(LISTENER_KEY_BLOCKED, emptySet()))
            }
        }
        listener.edit(commit = true) { ListenerKeys.forEach(::remove) }
    }

    companion object {
        const val PREFS_FILE = "garmin_watch_notifications"
        private const val KEY_ENABLED = "garmin_notifications_enabled"
        private const val KEY_BLOCKED = "garmin_notifications_blocked_packages"
        private const val KEY_DISCLOSURE_ACCEPTED = "garmin_notifications_disclosure_accepted"

        const val LISTENER_PREFS_FILE = "openwatch_notification_listener"
        private const val LISTENER_KEY_ENABLED = "forwarding_enabled"
        private const val LISTENER_KEY_BLOCKED = "blocked_packages"
        private const val LISTENER_KEY_WATCH = "watch_address"
        private val ListenerKeys = listOf(LISTENER_KEY_ENABLED, LISTENER_KEY_BLOCKED, LISTENER_KEY_WATCH)
    }
}
