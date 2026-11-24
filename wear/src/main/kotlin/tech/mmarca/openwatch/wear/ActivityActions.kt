package tech.mmarca.openwatch.wear

/**
 * What only the activity can do for a screen: ask for the grants the
 * recorders and the link need, and open the system's Bluetooth settings. One
 * object from [MainActivity], handed to the destinations that need it (the
 * settings, the phone, the sensor access and the status screen), rather than
 * a screen slot each.
 */
class ActivityActions(
    /** Asks for every grant still missing, the background heart rate last. */
    val requestGrants: () -> Unit,
    /** The system's Bluetooth settings, where a phone is paired again; the system settings where a watch has none. */
    val openBluetoothSettings: () -> Unit,
) {
    companion object {
        /** For a preview and a test: neither does anything. */
        val NONE = ActivityActions(requestGrants = {}, openBluetoothSettings = {})
    }
}
