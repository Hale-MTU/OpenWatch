package tech.mmarca.openwatch.devices.notifications

/**
 * Decides whether a notification is worth forwarding to the watch, and how much of it. Pure
 * logic, JVM-tested: it runs on every notification the phone posts. Which apps send is the
 * user's ([Config]); ongoing notifications are always dropped, and so is anything the posting
 * app or the user keeps off the lock screen. Rejecting here, before Bluetooth, is the feature's
 * biggest battery decision.
 */
object NotificationFilter {

    /** Android's `NotificationManager.IMPORTANCE_*` values, kept local. */
    const val IMPORTANCE_NONE = 0
    const val IMPORTANCE_MIN = 1
    const val IMPORTANCE_UNSPECIFIED = -1000

    /** Android's `Notification.VISIBILITY_*` and `Ranking.VISIBILITY_NO_OVERRIDE`, kept local. */
    const val VISIBILITY_PUBLIC = 1
    const val VISIBILITY_PRIVATE = 0
    const val VISIBILITY_SECRET = -1
    const val VISIBILITY_NO_OVERRIDE = -1000

    /** Android's `Notification.CATEGORY_CALL`, kept local. */
    const val CATEGORY_CALL = "call"

    /** Android's `NotificationManager.INTERRUPTION_FILTER_*` values. */
    const val INTERRUPTION_FILTER_ALL = 1
    const val INTERRUPTION_FILTER_PRIORITY = 2
    const val INTERRUPTION_FILTER_NONE = 3
    const val INTERRUPTION_FILTER_ALARMS = 4
    const val INTERRUPTION_FILTER_UNKNOWN = 0

    /** What the user configured, mirrored by the watch-notifications screen. */
    data class Config(
        val enabled: Boolean,
        val blockedPackages: Set<String>,
        /** False when no watch is paired: there is nowhere to send anything. */
        val hasWatch: Boolean,
        /** Only [allowedPackages] send, instead of every app but [blockedPackages]. */
        val onlyAllowed: Boolean = false,
        val allowedPackages: Set<String> = emptySet(),
        /** Content the user hides on the lock screen, for its channel, goes as its public version or title only. */
        val hideSensitiveContent: Boolean = true,
    ) {
        companion object {
            val disabled = Config(enabled = false, blockedPackages = emptySet(), hasWatch = false)
        }
    }

    /** One notification, reduced to what the decision actually depends on. */
    data class Candidate(
        val packageName: String,
        val title: String?,
        val body: String?,
        val ongoing: Boolean,
        val foregroundService: Boolean,
        val groupSummary: Boolean,
        val localOnly: Boolean,
        val channelImportance: Int,
        /** Android's `Notification.category`, or null. A call is ongoing yet still wanted. */
        val category: String? = null,
        /** The posting app's `Notification.visibility`; Android's default is private. */
        val visibility: Int = VISIBILITY_PRIVATE,
        /** The user's lock-screen setting for the notification's channel, `Ranking.getLockscreenVisibilityOverride`. */
        val lockscreenOverride: Int = VISIBILITY_NO_OVERRIDE,
        /** The posting app's public version, for a screen that must not show the rest. */
        val publicTitle: String? = null,
        val publicBody: String? = null,
    )

    /** What of a kept notification reaches the wrist. [redacted]: its own body and subtext stay on the phone. */
    data class Content(val title: String?, val body: String?, val redacted: Boolean)

    /** Why a notification was dropped. [KEEP] means it was not. */
    enum class Verdict {
        KEEP,
        DISABLED,
        NO_WATCH,
        OWN_PACKAGE,
        BLOCKED,
        NOT_ALLOWED,
        SECRET,
        ONGOING,
        GROUP_SUMMARY,
        LOCAL_ONLY,
        LOW_IMPORTANCE,
        EMPTY,
        DO_NOT_DISTURB,
    }

    /** Whether [candidate] should reach the watch. [ownPackage] stops our own reminders looping. */
    fun verdict(
        candidate: Candidate,
        config: Config,
        ownPackage: String,
        interruptionFilter: Int = INTERRUPTION_FILTER_ALL,
    ): Verdict {
        if (!config.enabled) return Verdict.DISABLED
        if (!config.hasWatch) return Verdict.NO_WATCH
        if (candidate.packageName == ownPackage) return Verdict.OWN_PACKAGE
        if (config.onlyAllowed) {
            if (candidate.packageName !in config.allowedPackages) return Verdict.NOT_ALLOWED
        } else if (candidate.packageName in config.blockedPackages) {
            return Verdict.BLOCKED
        }

        // What the posting app, or the user for its channel, keeps off even the lock screen
        // does not go to a wrist anyone can glance at.
        if (candidate.visibility == VISIBILITY_SECRET || candidate.lockscreenOverride == VISIBILITY_SECRET) {
            return Verdict.SECRET
        }

        // Reuses the phone's quiet hours. UNKNOWN means allow: swallowing is worse.
        if (interruptionFilter == INTERRUPTION_FILTER_NONE ||
            interruptionFilter == INTERRUPTION_FILTER_ALARMS
        ) {
            return Verdict.DO_NOT_DISTURB
        }

        // Media players and downloads repaint ongoing notifications constantly.
        // A ringing call is ongoing too, and the one worth a buzz.
        val call = candidate.category == CATEGORY_CALL
        if (!call && (candidate.ongoing || candidate.foregroundService)) return Verdict.ONGOING

        // The parent of a bundle: keeping it delivers every thread twice.
        if (candidate.groupSummary) return Verdict.GROUP_SUMMARY

        // The posting app explicitly said this should not leave the phone.
        if (candidate.localOnly) return Verdict.LOCAL_ONLY

        if (candidate.channelImportance == IMPORTANCE_NONE ||
            candidate.channelImportance == IMPORTANCE_MIN
        ) {
            return Verdict.LOW_IMPORTANCE
        }

        // Nothing to render on a watch face.
        if (candidate.title.isNullOrBlank() && candidate.body.isNullOrBlank()) {
            return Verdict.EMPTY
        }

        return Verdict.KEEP
    }

    /**
     * What of a kept [candidate] goes to the wrist: all of it, or, where the user set the
     * channel to hide sensitive content on the lock screen and [Config.hideSensitiveContent] is
     * on, the app's public version where it gave one and its title otherwise. Android has no
     * public mark for a one-time code; the channel's lock-screen setting is the user's own.
     */
    fun content(candidate: Candidate, config: Config): Content =
        if (config.hideSensitiveContent && candidate.lockscreenOverride == VISIBILITY_PRIVATE) {
            Content(title = candidate.publicTitle ?: candidate.title, body = candidate.publicBody, redacted = true)
        } else {
            Content(title = candidate.title, body = candidate.body, redacted = false)
        }

    /**
     * `GarminNotificationCategory` ordinals, duplicated so this file stays
     * dependency-free. The wire values are frozen.
     */
    object Category {
        const val OTHER = 0
        const val INCOMING_CALL = 1
        const val MISSED_CALL = 2
        const val VOICEMAIL = 3
        const val SOCIAL = 4
        const val SCHEDULE = 5
        const val EMAIL = 6
        const val NEWS = 7
        const val HEALTH_AND_FITNESS = 8
        const val BUSINESS_AND_FINANCE = 9
        const val LOCATION = 10
        const val ENTERTAINMENT = 11
        const val SMS = 12
    }

    /** Android's `Notification.category` as a GNCS category. Unrecognised is OTHER, not a guess. */
    fun categoryOrdinal(androidCategory: String?): Int = when (androidCategory) {
        CATEGORY_CALL -> Category.INCOMING_CALL
        "missed_call" -> Category.MISSED_CALL
        "voicemail" -> Category.VOICEMAIL
        "msg" -> Category.SMS
        "email" -> Category.EMAIL
        "social" -> Category.SOCIAL
        "event", "alarm", "reminder" -> Category.SCHEDULE
        "promo", "recommendation" -> Category.NEWS
        "navigation" -> Category.LOCATION
        "transport" -> Category.ENTERTAINMENT
        "workout" -> Category.HEALTH_AND_FITNESS
        else -> Category.OTHER
    }
}
