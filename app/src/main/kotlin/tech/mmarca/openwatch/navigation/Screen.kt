package tech.mmarca.openwatch.navigation

import android.net.Uri
import androidx.annotation.StringRes
import tech.mmarca.openwatch.R

const val WATCH_DEVICE_ID_ARG = "watchDeviceId"
const val WATCH_SETTINGS_SCREEN_ID_ARG = "screenId"
const val WATCH_POINT_LATITUDE_ARG = "lat"
const val WATCH_POINT_LONGITUDE_ARG = "lon"
const val WATCH_POINT_NAME_ARG = "name"

/** Set when another app shared something that held no position. */
const val WATCH_POINT_UNREADABLE_ARG = "unreadable"

sealed class Screen(
    val route: String,
    /** The app bar title. Null when the screen titles itself or shows no app bar. */
    @param:StringRes val titleRes: Int? = null,
) {
    /** The route without its query, for a screen with optional arguments. */
    val basePath: String get() = route.substringBefore('?')

    data object Home : Screen("home", R.string.app_name)
    data object HealthConnectSetup : Screen("health_connect", R.string.hc_setup_title)
    data object Settings : Screen("settings", R.string.settings_title)
    data object BodyProfile : Screen("settings/body_profile", R.string.body_profile_title)
    data object ConnectedApps : Screen("settings/connected_apps", R.string.connected_apps_title)

    /** The one scale's device view. Titled by the scale's name, like a watch. */
    data object ScaleDevice : Screen("scale")

    data object WatchDevice : Screen("watch/{$WATCH_DEVICE_ID_ARG}") {
        fun createRoute(watchDeviceId: String): String =
            "watch/${Uri.encode(watchDeviceId)}"
    }
    data object WatchData : Screen("watch/{$WATCH_DEVICE_ID_ARG}/data", R.string.settings_watch_data_title) {
        fun createRoute(watchDeviceId: String): String =
            "watch/${Uri.encode(watchDeviceId)}/data"
    }
    data object WatchNotifications : Screen("watch/{$WATCH_DEVICE_ID_ARG}/notifications", R.string.screen_watch_notifications) {
        fun createRoute(watchDeviceId: String): String =
            "watch/${Uri.encode(watchDeviceId)}/notifications"
    }

    /** Alarms kept on the phone, for a watch with no settings tree. */
    data object WatchAlarms : Screen("watch/{$WATCH_DEVICE_ID_ARG}/alarms", R.string.settings_watch_action_alarms) {
        fun createRoute(watchDeviceId: String): String =
            "watch/${Uri.encode(watchDeviceId)}/alarms"
    }

    /** The watch's own settings tree. */
    data object WatchSettings :
        Screen("watch/{$WATCH_DEVICE_ID_ARG}/settings/{$WATCH_SETTINGS_SCREEN_ID_ARG}", R.string.settings_watch_on_device_settings) {
        fun createRoute(watchDeviceId: String, screenId: Int): String =
            "watch/${Uri.encode(watchDeviceId)}/settings/$screenId"
    }

    /**
     * Send a point to a watch. Every argument is optional: a shared location
     * arrives with a position and no watch, the watch screen with a watch only.
     */
    data object WatchSendPoint : Screen(
        "watch_send_point?$WATCH_DEVICE_ID_ARG={$WATCH_DEVICE_ID_ARG}" +
            "&$WATCH_POINT_LATITUDE_ARG={$WATCH_POINT_LATITUDE_ARG}" +
            "&$WATCH_POINT_LONGITUDE_ARG={$WATCH_POINT_LONGITUDE_ARG}" +
            "&$WATCH_POINT_NAME_ARG={$WATCH_POINT_NAME_ARG}" +
            "&$WATCH_POINT_UNREADABLE_ARG={$WATCH_POINT_UNREADABLE_ARG}",
        R.string.settings_watch_point_title,
    ) {
        fun createRoute(
            watchDeviceId: String? = null,
            latitude: Double? = null,
            longitude: Double? = null,
            name: String? = null,
            unreadable: Boolean = false,
        ): String {
            val query = buildList {
                watchDeviceId?.let { add("$WATCH_DEVICE_ID_ARG=${Uri.encode(it)}") }
                if (latitude != null && longitude != null) {
                    add("$WATCH_POINT_LATITUDE_ARG=$latitude")
                    add("$WATCH_POINT_LONGITUDE_ARG=$longitude")
                }
                name?.let { add("$WATCH_POINT_NAME_ARG=${Uri.encode(it)}") }
                if (unreadable) add("$WATCH_POINT_UNREADABLE_ARG=true")
            }
            return if (query.isEmpty()) basePath else "$basePath?${query.joinToString("&")}"
        }
    }

    companion object {
        val all: List<Screen> by lazy {
            listOf(
                Home,
                HealthConnectSetup,
                Settings,
                BodyProfile,
                ConnectedApps,
                ScaleDevice,
                WatchDevice,
                WatchData,
                WatchNotifications,
                WatchAlarms,
                WatchSettings,
                WatchSendPoint,
            )
        }

        private val titlesByBasePath: Map<String, Int?> by lazy {
            all.associate { it.basePath to it.titleRes }
        }

        /** The title of the screen at [basePath]. Null when that screen titles itself. */
        @StringRes
        fun titleResFor(basePath: String?): Int? = titlesByBasePath[basePath]
    }
}
