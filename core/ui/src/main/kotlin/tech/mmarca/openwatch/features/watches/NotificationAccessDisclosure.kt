package tech.mmarca.openwatch.features.watches

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import tech.mmarca.openwatch.core.ui.R

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
