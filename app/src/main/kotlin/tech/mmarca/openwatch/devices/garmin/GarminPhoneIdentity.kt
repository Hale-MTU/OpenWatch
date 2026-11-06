package tech.mmarca.openwatch.devices.garmin

/**
 * How this phone introduces itself in the handshake. Cosmetic: the watch
 * only shows it. Fixed strings, matching the Flutter build.
 */
data class GarminPhoneIdentity(
    val bluetoothName: String = "OpenWatch",
    val manufacturer: String = "OpenWatch",
    val model: String = "Android",
)
