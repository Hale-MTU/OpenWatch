package tech.mmarca.openwatch.features.watches

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.core.presentation.ScreenError
import tech.mmarca.openwatch.core.presentation.resolve
import tech.mmarca.openwatch.devices.wearos.WearOsAppStatus
import tech.mmarca.openwatch.devices.wearos.WearOsCompanionStatus
import tech.mmarca.openwatch.devices.wearos.messageRes
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.ui.components.InlineGrantPrompt
import tech.mmarca.openwatch.ui.components.OpenWatchCard
import tech.mmarca.openwatch.ui.components.OpenWatchOutlinedButton
import tech.mmarca.openwatch.ui.components.PermissionCallout
import tech.mmarca.openwatch.ui.components.formatDeviceTime
import tech.mmarca.openwatch.ui.theme.Spacing
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncUiState
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncController

@Composable
internal fun WearOsStatusCard(
    status: WearOsCompanionStatus,
    error: ScreenError?,
    isChecking: Boolean,
    onCheck: () -> Unit,
    onGrantBluetooth: () -> Unit,
    /** The heart rate the watch streams while this screen is open; null while none arrives. */
    liveHeartRateBpm: Int? = null,
    /** Set while the watch has no companion association: the card asks for it. */
    onAssociate: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    if (error == ScreenError.PermissionDenied) {
        PermissionCallout(
            title = stringResource(R.string.message_missing_permissions_title),
            body = stringResource(R.string.settings_watch_wearos_bt_permission),
            onGrant = onGrantBluetooth,
        )
        return
    }
    OpenWatchCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (status.isPaired) {
                        Icons.Outlined.CheckCircle
                    } else {
                        Icons.Outlined.RadioButtonUnchecked
                    },
                    contentDescription = null,
                    tint = if (status.isPaired) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(WearOsStatusIconSize),
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = if (status.isPaired) {
                        stringResource(R.string.settings_watch_wearos_bt_paired)
                    } else {
                        stringResource(R.string.settings_watch_wearos_bt_not_paired)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (status.isAppRunning) {
                        Icons.Outlined.CheckCircle
                    } else {
                        Icons.Outlined.RadioButtonUnchecked
                    },
                    contentDescription = null,
                    tint = if (status.isAppRunning) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(WearOsStatusIconSize),
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = when (status.appStatus) {
                        WearOsAppStatus.APP_RUNNING -> status.watchName
                            ?.let { stringResource(R.string.settings_watch_wearos_linked_to, it) }
                            ?: stringResource(R.string.settings_watch_wearos_app_running)
                        WearOsAppStatus.NOT_PAIRED -> stringResource(R.string.settings_watch_not_connected)
                        else -> stringResource(status.appStatus.messageRes())
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (status.isAppRunning) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            liveHeartRateBpm?.let { bpm ->
                Text(
                    text = stringResource(R.string.settings_watch_live_now, bpm),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (status.bondLost || status.appStatus == WearOsAppStatus.NOT_PAIRED) {
                Text(
                    text = stringResource(R.string.settings_watch_wearos_status_bond_lost),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OpenWatchOutlinedButton(
                    onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_sensors_open_bluetooth))
                }
            }

            if (onAssociate != null) {
                InlineGrantPrompt(
                    message = stringResource(R.string.settings_watch_wearos_companion_missing),
                    actionLabel = stringResource(R.string.settings_watch_wearos_companion_link),
                    onGrant = onAssociate,
                )
            }

            error.resolve()?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            OpenWatchOutlinedButton(
                onClick = onCheck,
                enabled = !isChecking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(WearOsButtonIconSize),
                        strokeWidth = WearOsProgressStroke,
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.settings_watch_wearos_checking))
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(WearOsButtonIconSize),
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.settings_watch_wearos_check_app))
                }
            }
        }
    }
}

/**
 * The sync for a Wear OS watch: what it does, when it last ran, what the
 * last run brought (heart rate samples and estimated nights), and the
 * button. The sync itself is the shared [DeviceSyncController] flow; this
 * card only words it.
 */
@Composable
internal fun WearOsSyncCard(
    device: BleSensorDevice,
    sync: DeviceSyncUiState,
    onSync: () -> Unit,
    onAllowGrants: (Set<String>) -> Unit,
) {
    val syncingThis = sync.isSyncingDevice(device.id)
    OpenWatchCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = stringResource(R.string.settings_watch_wearos_sync_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            val syncedAt = device.lastSyncedAt
            Text(
                text = if (syncedAt == null) {
                    stringResource(R.string.settings_watch_never_synced)
                } else {
                    stringResource(R.string.settings_watch_last_synced, formatDeviceTime(syncedAt))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            sync.lastFor(device.id)?.let { SyncOutcomeSummary(it, onAllowGrants) }
            sync.failureFor(device.id)?.toScreenError().resolve()?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OpenWatchOutlinedButton(
                onClick = onSync,
                // One sync at a time, whichever watch it is for.
                enabled = !sync.isSyncing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (syncingThis) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(WearOsButtonIconSize),
                        strokeWidth = WearOsProgressStroke,
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.settings_watch_wearos_syncing))
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(WearOsButtonIconSize),
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.settings_watch_wearos_sync_now))
                }
            }
        }
    }
}

private val WearOsStatusIconSize = 20.dp
private val WearOsButtonIconSize = 18.dp
private val WearOsProgressStroke = 2.dp
