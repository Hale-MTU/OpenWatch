package tech.mmarca.openwatch.navigation

import kotlinx.serialization.Serializable

/**
 * One paired device, of any family: a watch or bike computer opens the device screen with its
 * family's panel, the scale its own screen. Titled by the device's name. Declared here, with the
 * screens that read it back; the app's graph holds it.
 */
@Serializable
data class DeviceDetail(val deviceId: String)
