package tech.mmarca.openwatch.navigation

import androidx.annotation.StringRes
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import kotlin.reflect.KClass
import kotlinx.serialization.Serializable
import tech.mmarca.openwatch.R

/*
 * The app's destinations. Each is a @Serializable type whose properties are its arguments:
 * Navigation encodes them, a navigate call that leaves one out or passes the wrong type does not
 * compile, and a destination's ViewModel reads them back with `savedStateHandle.toRoute<T>()`.
 */

@Serializable
data object Home

/** The first run: Health Connect set up, then Home. The start destination until it is done. */
@Serializable
data object Onboarding

/** The same setup as a page of Settings, reached from Home and Settings: back, no Continue. */
@Serializable
data object HealthConnectSetup

@Serializable
data object Settings

@Serializable
data object BodyProfile

@Serializable
data object ConnectedApps

/** The one scale's device view. Titled by the scale's name, like a watch. */
@Serializable
data object ScaleDevice

/** A watch or bike computer: the part every device has, and its family's panel. Titled by the device's name. */
@Serializable
data class WatchDevice(val deviceId: String)

@Serializable
data class WatchData(val deviceId: String)

@Serializable
data class WatchNotifications(val deviceId: String)

/** Alarms kept on the phone, for a watch with no settings tree. */
@Serializable
data class WatchAlarms(val deviceId: String)

/** One screen of the watch's own settings tree; a row that leads deeper pushes another. */
@Serializable
data class WatchSettings(val deviceId: String, val screenId: Int)

/**
 * Send a point to a watch. Every argument is optional: a shared location arrives with a
 * position and no watch, the watch screen with a watch only. [unreadable] is set when another
 * app shared something that held no position.
 */
@Serializable
data class WatchSendPoint(
    val deviceId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val name: String? = null,
    val unreadable: Boolean = false,
)

/** The app bar title of each destination that does not title itself. */
private val routeTitles: List<Pair<KClass<*>, Int>> = listOf(
    Home::class to R.string.app_name,
    Onboarding::class to R.string.hc_setup_title,
    HealthConnectSetup::class to R.string.hc_setup_title,
    Settings::class to R.string.settings_title,
    BodyProfile::class to R.string.body_profile_title,
    ConnectedApps::class to R.string.connected_apps_title,
    WatchData::class to R.string.settings_watch_data_title,
    WatchNotifications::class to R.string.screen_watch_notifications,
    WatchAlarms::class to R.string.settings_watch_action_alarms,
    WatchSettings::class to R.string.settings_watch_on_device_settings,
    WatchSendPoint::class to R.string.settings_watch_point_title,
)

/** This destination's title; null for one that titles itself (a device, by its name). */
@StringRes
fun NavDestination.titleRes(): Int? = routeTitles.firstOrNull { (route, _) -> hasRoute(route) }?.second

/** The two roots, Home and the first run's setup: nothing to go back to. */
val NavDestination.isRoot: Boolean
    get() = hasRoute(Home::class) || hasRoute(Onboarding::class)
