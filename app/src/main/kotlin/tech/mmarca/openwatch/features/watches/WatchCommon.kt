package tech.mmarca.openwatch.features.watches

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Duration
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.domain.model.BleSensorCapability
import tech.mmarca.openwatch.ui.theme.Spacing

/** Pieces the device view and the watch-data screen share. */

/** One icon action in the action band. Actions are verbs; settings are rows further down. */
@Composable
internal fun WatchAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, enabled = enabled) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(Spacing.xl),
                    strokeWidth = WatchProgressStroke,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            } else {
                // Named, or a screen reader announces four identical buttons.
                Icon(imageVector = icon, contentDescription = label)
            }
        }
        Spacer(modifier = Modifier.padding(top = Spacing.xs))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            },
        )
    }
}

/** `7h 50m`, `17 min`, `45s`: the coarsest unit that still says something. */
internal fun formatWatchDuration(duration: Duration): String {
    val minutes = duration.toMinutes()
    if (minutes >= 60) {
        val hours = minutes / 60
        val rest = minutes % 60
        return if (rest == 0L) "${hours}h" else "${hours}h ${rest}m"
    }
    if (minutes > 0) return "$minutes min"
    return "${duration.seconds}s"
}

/** The Sensors screen's capability label, re-declared here (it is private there). */
@Composable
internal fun capabilityLabel(
    capability: BleSensorCapability,
): String = when (capability) {
    BleSensorCapability.HEART_RATE ->
        stringResource(R.string.settings_sensors_capability_heart_rate)

    BleSensorCapability.CYCLING_CADENCE ->
        stringResource(R.string.settings_sensors_capability_cycling_cadence)

    BleSensorCapability.CYCLING_POWER ->
        stringResource(R.string.settings_sensors_capability_cycling_power)

    BleSensorCapability.CYCLING_SPEED_DISTANCE ->
        stringResource(R.string.settings_sensors_capability_cycling_speed)

    BleSensorCapability.RUNNING_SPEED_CADENCE ->
        stringResource(R.string.settings_sensors_capability_running_speed_cadence)
}

/** The small in-row progress spinner's stroke. */
private val WatchProgressStroke = 2.dp

/**
 * The prominent disclosure Google Play requires before notification access.
 * Each feature that asks for the grant says what it reads. Dismissing declines.
 */
@Composable
fun NotificationAccessDisclosureDialog(
    title: String,
    body: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDecline,
        icon = { Icon(imageVector = Icons.Outlined.PrivacyTip, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(body)
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = onAccept) {
                Text(stringResource(R.string.settings_watch_notifications_disclosure_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(stringResource(R.string.settings_watch_notifications_disclosure_decline))
            }
        },
    )
}
