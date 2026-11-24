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
import tech.mmarca.openwatch.features.watches.garmin.R as GarminR

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

/** The app bar title of each destination that does not title itself. */
private val routeTitles: List<Pair<KClass<*>, Int>> = listOf(
    Home::class to R.string.app_name,
    Onboarding::class to R.string.hc_setup_title,
    HealthConnectSetup::class to R.string.hc_setup_title,
    Settings::class to R.string.settings_title,
    BodyProfile::class to SettingsR.string.body_profile_title,
    ConnectedApps::class to SettingsR.string.connected_apps_title,
    WatchData::class to GarminR.string.settings_watch_data_title,
    WatchNotifications::class to R.string.screen_watch_notifications,
    WatchAlarms::class to GarminR.string.settings_watch_action_alarms,
    WatchSettings::class to GarminR.string.settings_watch_on_device_settings,
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
