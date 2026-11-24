package tech.mmarca.openwatch.navigation

import kotlinx.serialization.Serializable

/*
 * The Garmin watch's own destinations, which its panel opens through DeviceScreenHost.navigate
 * and its screens read back with toRoute. Declared here with the screens, under the names they
 * have always had; the app's graph holds them.
 */

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
