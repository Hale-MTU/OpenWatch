package tech.mmarca.openwatch.navigation

import android.net.Uri
import androidx.annotation.StringRes
import tech.mmarca.openwatch.R

const val WATCH_DEVICE_ID_ARG = "watchDeviceId"

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

    data object WatchDevice : Screen("watch/{$WATCH_DEVICE_ID_ARG}") {
        fun createRoute(watchDeviceId: String): String =
            "watch/${Uri.encode(watchDeviceId)}"
    }

    companion object {
        val all: List<Screen> by lazy {
            listOf(
                Home,
                HealthConnectSetup,
                Settings,
                BodyProfile,
                WatchDevice,
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
