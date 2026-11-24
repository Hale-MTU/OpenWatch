package tech.mmarca.openwatch.wear.features.settings

import tech.mmarca.openwatch.wear.UnitSystem
import tech.mmarca.openwatch.wear.WearLinkUiState

/** What the watch app may use, one grant each. */
internal data class SensorAccess(
    val heartRate: Boolean,
    /** True on a release without a background grant of its own: the foreground one covers it. */
    val heartRateInBackground: Boolean,
    /** Null on a watch without a step counter, which needs no grant. */
    val steps: Boolean?,
    /** The phone link's Bluetooth grant. */
    val nearbyDevices: Boolean,
) {
    /** Every grant the recorders and the link need is in. */
    val complete: Boolean
        get() = heartRate && heartRateInBackground && steps != false && nearbyDevices
}

/** What the settings screens show: the link as the service keeps it, the grants and the unit system, each null until read. */
internal data class SettingsUiState(
    val link: WearLinkUiState = WearLinkUiState(),
    val access: SensorAccess? = null,
    val unitSystem: UnitSystem? = null,
) {
    /**
     * A phone counts as connected while one is allowed, still paired and the
     * link listens. Not while a request is open: the phone holds a connection
     * only for as long as a pull takes, so no client at this moment says
     * nothing about the link.
     */
    val phoneConnected: Boolean
        get() = link.listening && link.trusted.any { phone -> link.bondLost.none { it.address == phone.address } }

    /** Whether every grant is in; null until the grants are read. */
    val sensorAccessGranted: Boolean?
        get() = access?.complete
}
