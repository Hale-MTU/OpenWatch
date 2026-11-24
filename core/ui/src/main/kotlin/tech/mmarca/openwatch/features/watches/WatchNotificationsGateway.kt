package tech.mmarca.openwatch.features.watches

/** One app the user can silence. */
data class InstalledApp(
    val packageName: String,
    val label: String,
)

/**
 * The Android-touching edge of watch notifications, behind an interface so
 * the view-model's gate logic is JVM-testable. Settings asks it for the grant, the watch's
 * screens for the rest; `AndroidWatchNotificationsGateway` is the real one.
 */
interface WatchNotificationsGateway {

    /** Whether Android granted notification access. Only the settings screen can, so it is polled. */
    fun isNotificationAccessGranted(): Boolean

    /** Opens Android's own notification-access settings screen. */
    fun openNotificationAccessSettings()

    /** Every launchable app, via the manifest `<queries>`, never QUERY_ALL_PACKAGES. */
    fun listLaunchableApps(): List<InstalledApp>

    /** Tells the forwarder the setting changed, so a link it no longer needs is let go. */
    fun pushConfiguration()
}
