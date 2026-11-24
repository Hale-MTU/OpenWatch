package tech.mmarca.openwatch.features.watches

import tech.mmarca.openwatch.features.device.R
import tech.mmarca.openwatch.core.presentation.ScreenError
import tech.mmarca.openwatch.devices.core.sync.SyncFailure

/** The words for a failed sync, whichever integration ran it. */
fun SyncFailure.toScreenError(): ScreenError = ScreenError.Text(
    when (this) {
        SyncFailure.HealthConnectPermission -> R.string.sync_failed_health_connect_permission
        SyncFailure.BluetoothPermission -> R.string.sync_failed_bluetooth_permission
        SyncFailure.BluetoothOff -> tech.mmarca.openwatch.devices.core.R.string.settings_watch_wearos_status_bluetooth_off
        SyncFailure.SyncPaused -> R.string.health_connect_sync_paused
        SyncFailure.RadioBusy -> R.string.settings_watch_point_failed_busy
        SyncFailure.Unreachable -> R.string.sync_failed_unreachable
        SyncFailure.Interrupted -> R.string.settings_watch_wearos_fail_partial
        SyncFailure.RateLimited -> R.string.sync_failed_rate_limited
        SyncFailure.NotSaved -> R.string.sync_failed_not_saved
        is SyncFailure.Described -> message
        is SyncFailure.Unknown -> R.string.settings_watch_wearos_fail_generic
    },
)
