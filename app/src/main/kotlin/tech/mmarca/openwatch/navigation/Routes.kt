package tech.mmarca.openwatch.navigation

import androidx.annotation.StringRes
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import kotlin.reflect.KClass
import kotlinx.serialization.Serializable
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.features.settings.R as SettingsR

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

/**
 * One paired device, of any family: a watch or bike computer opens the device screen with its
 * family's panel, the scale its own screen. Titled by the device's name.
 */
@Serializable
data class DeviceDetail(val deviceId: String)

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
    BodyProfile::class to SettingsR.string.body_profile_title,
    ConnectedApps::class to SettingsR.string.connected_apps_title,
    WatchData::class to R.string.settings_watch_data_title,
    WatchNotifications::class to R.string.screen_watch_notifications,
    WatchAlarms::class to R.string.settings_watch_action_alarms,
    WatchSettings::class to R.string.settings_watch_on_device_settings,
    WatchSendPoint::class to R.string.settings_watch_point_title,
)

/** This destination's title; null for one that titles itself (a device, by its name). */
@StringRes
fun NavDestination.titleRes(): Int? = routeTitles.firstOrNull { (route, _) -> hasRoute(route) }?.second

/** The paired device this entry's screen is about, which the list beside it marks; null for a screen about none. */
fun NavBackStackEntry.deviceId(): String? = when {
    destination.hasRoute(DeviceDetail::class) -> toRoute<DeviceDetail>().deviceId
    destination.hasRoute(WatchData::class) -> toRoute<WatchData>().deviceId
    destination.hasRoute(WatchNotifications::class) -> toRoute<WatchNotifications>().deviceId
    destination.hasRoute(WatchAlarms::class) -> toRoute<WatchAlarms>().deviceId
    destination.hasRoute(WatchSettings::class) -> toRoute<WatchSettings>().deviceId
    destination.hasRoute(WatchSendPoint::class) -> toRoute<WatchSendPoint>().deviceId
    else -> null
}

/** The two roots, Home and the first run's setup: nothing to go back to. */
val NavDestination.isRoot: Boolean
    get() = hasRoute(Home::class) || hasRoute(Onboarding::class)
