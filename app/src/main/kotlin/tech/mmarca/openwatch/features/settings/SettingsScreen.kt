package tech.mmarca.openwatch.features.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.mmarca.openwatch.PermissionsRationaleActivity
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.features.watches.NotificationAccessDisclosureDialog
import tech.mmarca.openwatch.ui.components.OpenWatchCard
import tech.mmarca.openwatch.ui.components.SwitchCard
import tech.mmarca.openwatch.ui.theme.Spacing

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenHealthConnect: () -> Unit,
    onOpenBodyProfile: () -> Unit,
    onOpenConnectedApps: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showNotificationDisclosure by remember { mutableStateOf(false) }
    val privacyPolicyUrl = stringResource(R.string.settings_privacy_policy_url)
    val noBrowserMessage = stringResource(R.string.settings_privacy_policy_no_browser, privacyPolicyUrl)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        SettingsRow(
            title = stringResource(R.string.home_health_connect),
            body = stringResource(R.string.home_health_connect_body),
            onClick = onOpenHealthConnect,
        )
        SettingsRow(
            title = stringResource(R.string.body_profile_title),
            body = stringResource(R.string.body_profile_settings_body),
            onClick = onOpenBodyProfile,
        )
        SwitchCard(
            title = stringResource(R.string.hc_setup_sync_title),
            body = stringResource(R.string.hc_setup_sync_body),
            checked = state.syncEnabled,
            onCheckedChange = viewModel::setSyncEnabled,
            modifier = Modifier.fillMaxWidth(),
        )
        SwitchCard(
            title = stringResource(R.string.settings_respiratory_rate_title),
            body = stringResource(R.string.settings_respiratory_rate_body),
            checked = state.writeRespiratoryRate,
            onCheckedChange = viewModel::setWriteRespiratoryRate,
            modifier = Modifier.fillMaxWidth(),
        )
        SettingsRow(
            title = stringResource(R.string.connected_apps_title),
            body = stringResource(R.string.connected_apps_settings_body),
            onClick = onOpenConnectedApps,
        )
        SettingsRow(
            title = stringResource(R.string.settings_notification_access_title),
            body = stringResource(
                if (state.notificationAccessGranted) {
                    R.string.settings_notification_access_granted
                } else {
                    R.string.settings_notification_access_body
                },
            ),
            onClick = {
                if (state.notificationAccessGranted) {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } else {
                    showNotificationDisclosure = true
                }
            },
        )
        SettingsRow(
            title = stringResource(R.string.health_data_title),
            body = stringResource(R.string.health_data_open_body),
            onClick = { context.startActivity(Intent(context, PermissionsRationaleActivity::class.java)) },
        )
        SettingsRow(
            title = stringResource(R.string.privacy_policy_title),
            body = stringResource(R.string.settings_privacy_policy_body),
            onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, privacyPolicyUrl.toUri()))
                } catch (_: ActivityNotFoundException) {
                    // This app needs no browser, so some phones that run it have none.
                    Toast.makeText(context, noBrowserMessage, Toast.LENGTH_LONG).show()
                }
            },
        )
        Text(
            text = stringResource(R.string.settings_about_version, state.versionName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.md),
        )
        Text(
            text = stringResource(R.string.settings_app_publisher),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.settings_about_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showNotificationDisclosure) {
        NotificationAccessDisclosureDialog(
            title = stringResource(R.string.settings_watch_notifications_disclosure_title),
            body = stringResource(R.string.settings_watch_notifications_disclosure_body),
            onAccept = {
                showNotificationDisclosure = false
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            onDecline = { showNotificationDisclosure = false },
        )
    }
}

@Composable
private fun SettingsRow(title: String, body: String, onClick: () -> Unit) {
    OpenWatchCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(text = body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
