package tech.mmarca.openwatch.devices.core.sync

import androidx.annotation.StringRes

/**
 * Why a sync failed, for every integration alike. The device layer names the cause; the screen
 * picks the words (`features/watches/SyncFailureText.kt`), so no sentence crosses the boundary
 * and a missing grant stays a type.
 */
sealed interface SyncFailure {

    /** Whether the schedule's backoff can succeed without the user doing anything first. */
    val transient: Boolean get() = false

    /** Health Connect refused a write or read for want of a grant. */
    data object HealthConnectPermission : SyncFailure

    /** The nearby-devices grant, which every Bluetooth link needs, is missing. */
    data object BluetoothPermission : SyncFailure

    data object BluetoothOff : SyncFailure

    /** The user switched Health Connect sync off in the app. */
    data object SyncPaused : SyncFailure

    /** Another task holds the device's radio. */
    data object RadioBusy : SyncFailure {
        override val transient: Boolean get() = true
    }

    /** The device did not answer: off, out of range, or the link dropped before anything came. */
    data object Unreachable : SyncFailure {
        override val transient: Boolean get() = true
    }

    /** The device stopped part-way. What arrived was saved; the next sync continues. */
    data object Interrupted : SyncFailure {
        override val transient: Boolean get() = true
    }

    /** Health Connect refused for this app's call quota. What was written stays; the next sync goes on from there. */
    data object RateLimited : SyncFailure {
        override val transient: Boolean get() = true
    }

    /** Health Connect would not take some records for a reason other than a grant; the next sync tries again. */
    data object NotSaved : SyncFailure {
        override val transient: Boolean get() = true
    }

    /**
     * A state only the integration can name, such as a watch app waiting for the user's
     * approval, in its own translated [message].
     */
    data class Described(@param:StringRes val message: Int, override val transient: Boolean = false) : SyncFailure

    /** Anything else. Retried, as a dropped link usually is. */
    data class Unknown(val cause: Throwable) : SyncFailure {
        override val transient: Boolean get() = true
    }
}
