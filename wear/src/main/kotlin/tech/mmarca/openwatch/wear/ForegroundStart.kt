package tech.mmarca.openwatch.wear

import android.content.pm.ServiceInfo

/**
 * The foreground service types [WearAppService] asks for, in the order it tries them.
 *
 * The full set first. If the system refuses it (a `health` start from the background
 * without the background sensor grant, say), the link alone: `connectedDevice` needs only
 * the Bluetooth grant every start already checked. Refusing the whole service would stop
 * the phone link with the recorders, and nothing would start it again until the app is
 * opened. Pure, so it is tested without a watch.
 */
internal object ForegroundStart {

    const val LINK: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

    /** [wanted] first, then the link alone; never the same set twice. */
    fun attempts(wanted: Int): List<Int> = listOf(wanted or LINK, LINK).distinct()

    /** Whether the running service lacks the `health` type it could now have. */
    fun healthMissing(running: Int, sensing: Boolean, healthTypeAvailable: Boolean): Boolean =
        healthTypeAvailable && sensing && running and ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH == 0
}
