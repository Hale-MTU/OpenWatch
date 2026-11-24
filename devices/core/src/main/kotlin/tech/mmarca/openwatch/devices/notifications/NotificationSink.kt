package tech.mmarca.openwatch.devices.notifications

/**
 * Who forwards the notifications [OpenWatchNotificationListenerService] buffered. The
 * listener is the platform's and names no watch; each integration that mirrors
 * notifications implements this and is bound in `di/DevicesModule`.
 */
fun interface NotificationSink {
    /** Something was buffered in [NotificationStore]. Called on the listener's thread. */
    fun onNotificationsPending()

    /** Whether a watch is paired to send to; with none, nothing is captured. */
    fun hasTarget(): Boolean = true
}
