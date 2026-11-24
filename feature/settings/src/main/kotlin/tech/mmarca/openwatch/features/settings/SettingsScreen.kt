package tech.mmarca.openwatch.features.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tech.mmarca.openwatch.core.ui.R as UiR
import tech.mmarca.openwatch.features.watches.NotificationAccessDisclosureDialog
import tech.mmarca.openwatch.ui.components.LocalSnackbarHostState
import tech.mmarca.openwatch.ui.components.OpenWatchCard
import tech.mmarca.openwatch.ui.components.ScreenColumn
import tech.mmarca.openwatch.ui.components.SwitchCard
import tech.mmarca.openwatch.ui.theme.OpenWatchTheme
import tech.mmarca.openwatch.ui.theme.Spacing

/**
 * The Settings destination: the only place that knows the ViewModel. It starts the system
 * screens the rows lead to and owns the notification disclosure; [SettingsScreen] draws.
 */
@Composable
fun SettingsRoute(
    onOpenHealthConnect: () -> Unit,
    onOpenBodyProfile: () -> Unit,
    onOpenConnectedApps: () -> Unit,
    /** The list of the health data the app reads and writes, the app's own activity. */
    onOpenHealthData: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showNotificationDisclosure by rememberSaveable { mutableStateOf(false) }
    val privacyPolicyUrl = stringResource(R.string.settings_privacy_policy_url)
    val noBrowserMessage = stringResource(R.string.settings_privacy_policy_no_browser, privacyPolicyUrl)
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    SettingsScreen(
        state = state,
        onOpenHealthConnect = onOpenHealthConnect,
        onOpenBodyProfile = onOpenBodyProfile,
        onOpenConnectedApps = onOpenConnectedApps,
        onSyncEnabledChange = viewModel::setSyncEnabled,
        onWriteRespiratoryRateChange = viewModel::setWriteRespiratoryRate,
        onWriteSleepBesideOthersChange = viewModel::setWriteSleepBesideOthers,
        onOpenNotificationAccess = {
            if (state.notificationAccessGranted) {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } else {
                showNotificationDisclosure = true
            }
        },
        onOpenHealthData = onOpenHealthData,
        onOpenPrivacyPolicy = {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, privacyPolicyUrl.toUri()))
            } catch (_: ActivityNotFoundException) {
                // This app needs no browser, so some phones that run it have none.
                scope.launch { snackbar.showSnackbar(noBrowserMessage, duration = SnackbarDuration.Long) }
            }
        },
    )

    if (showNotificationDisclosure) {
        NotificationAccessDisclosureDialog(
            title = stringResource(UiR.string.settings_watch_notifications_disclosure_title),
            body = stringResource(UiR.string.settings_watch_notifications_disclosure_body),
            onAccept = {
                showNotificationDisclosure = false
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            onDecline = { showNotificationDisclosure = false },
        )
    }
}

/** Settings as drawn from [state]: every action goes out through its lambda. */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onOpenHealthConnect: () -> Unit,
    onOpenBodyProfile: () -> Unit,
    onOpenConnectedApps: () -> Unit,
    onSyncEnabledChange: (Boolean) -> Unit,
    onWriteRespiratoryRateChange: (Boolean) -> Unit,
    onWriteSleepBesideOthersChange: (Boolean) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenHealthData: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        SettingsRow(
            title = stringResource(UiR.string.home_health_connect),
            body = stringResource(UiR.string.home_health_connect_body),
            onClick = onOpenHealthConnect,
        )
        SettingsRow(
            title = stringResource(R.string.body_profile_title),
            body = stringResource(R.string.body_profile_settings_body),
            onClick = onOpenBodyProfile,
        )
        SwitchCard(
            title = stringResource(UiR.string.hc_setup_sync_title),
            body = stringResource(UiR.string.hc_setup_sync_body),
            checked = state.syncEnabled,
            onCheckedChange = onSyncEnabledChange,
            modifier = Modifier.fillMaxWidth(),
        )
        SwitchCard(
            title = stringResource(R.string.settings_respiratory_rate_title),
            body = stringResource(R.string.settings_respiratory_rate_body),
            checked = state.writeRespiratoryRate,
            onCheckedChange = onWriteRespiratoryRateChange,
            modifier = Modifier.fillMaxWidth(),
        )
        SwitchCard(
            title = stringResource(R.string.settings_sleep_beside_others_title),
            body = stringResource(R.string.settings_sleep_beside_others_body),
            checked = state.writeSleepBesideOthers,
            onCheckedChange = onWriteSleepBesideOthersChange,
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
            onClick = onOpenNotificationAccess,
        )
        SettingsRow(
            title = stringResource(UiR.string.health_data_title),
            body = stringResource(R.string.health_data_open_body),
            onClick = onOpenHealthData,
        )
        SettingsRow(
            title = stringResource(R.string.privacy_policy_title),
            body = stringResource(R.string.settings_privacy_policy_body),
            onClick = onOpenPrivacyPolicy,
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
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    OpenWatchTheme {
        SettingsScreen(
            state = SettingsUiState(syncEnabled = true, notificationAccessGranted = true, versionName = "0.4.1"),
            onOpenHealthConnect = {},
            onOpenBodyProfile = {},
            onOpenConnectedApps = {},
            onSyncEnabledChange = {},
            onWriteRespiratoryRateChange = {},
            onWriteSleepBesideOthersChange = {},
            onOpenNotificationAccess = {},
            onOpenHealthData = {},
            onOpenPrivacyPolicy = {},
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
