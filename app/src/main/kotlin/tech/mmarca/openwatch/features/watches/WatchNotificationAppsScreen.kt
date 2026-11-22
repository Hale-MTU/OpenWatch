package tech.mmarca.openwatch.features.watches

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.ui.components.OpenWatchCard
import tech.mmarca.openwatch.ui.components.OpenWatchCardStyle
import tech.mmarca.openwatch.ui.components.ScreenErrorContent
import tech.mmarca.openwatch.ui.components.SwitchRow
import tech.mmarca.openwatch.ui.theme.OpenWatchTheme
import tech.mmarca.openwatch.ui.theme.Spacing

/**
 * The watch-notifications destination: the only place that knows the ViewModel. It loads the
 * app list, re-reads access on resume, and owns the disclosure and the access-settings launch;
 * [WatchNotificationAppsScreen] draws.
 */
@Composable
fun WatchNotificationAppsRoute(viewModel: WatchNotificationAppsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Resolving every app's label is a round trip per app, so it waits for this screen.
    LaunchedEffect(Unit) { viewModel.loadApps() }

    // Access is granted on a system screen with no callback; re-read on resume.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    if (state.showDisclosure) {
        NotificationAccessDisclosureDialog(
            title = stringResource(R.string.settings_watch_notifications_disclosure_title),
            body = stringResource(R.string.settings_watch_notifications_disclosure_body),
            onAccept = viewModel::acceptDisclosure,
            onDecline = viewModel::declineDisclosure,
        )
    }

    WatchNotificationAppsScreen(
        state = state,
        onEnabledChange = viewModel::setEnabled,
        onOpenAccessSettings = viewModel::openAccessSettings,
        onOnlyAllowedChange = viewModel::setOnlyAllowed,
        onHideSensitiveChange = viewModel::setHideSensitiveContent,
        onSendsChange = viewModel::setSends,
    )
}

/**
 * Watch notification forwarding: the master switch, the two gates and the
 * per-app blocklist. A blocklist, so a new messaging app is not silent.
 */
@Composable
fun WatchNotificationAppsScreen(
    state: WatchNotificationsUiState,
    onEnabledChange: (Boolean) -> Unit,
    onOpenAccessSettings: () -> Unit,
    onOnlyAllowedChange: (Boolean) -> Unit,
    onHideSensitiveChange: (Boolean) -> Unit,
    onSendsChange: (packageName: String, sends: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item(key = "master") {
            MasterSwitchCard(
                state = state,
                onToggle = onEnabledChange,
                onOpenAccessSettings = onOpenAccessSettings,
            )
        }

        if (state.active) {
            item(key = "options") {
                ForwardingOptionsCard(
                    state = state,
                    onOnlyAllowed = onOnlyAllowedChange,
                    onHideSensitive = onHideSensitiveChange,
                )
            }
            item(key = "intro") {
                Text(
                    text = stringResource(
                        if (state.onlyAllowed) {
                            R.string.settings_watch_notifications_apps_intro_allowed
                        } else {
                            R.string.settings_watch_notifications_apps_intro
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                )
            }
            when {
                state.loadingApps -> item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.xxxl),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                state.appsError != null -> item(key = "error") {
                    ScreenErrorContent(screenError = state.appsError, modifier = Modifier.padding(Spacing.lg))
                }
                state.apps.isEmpty() -> item(key = "empty") {
                    Text(
                        text = stringResource(R.string.settings_watch_notifications_apps_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(Spacing.lg),
                    )
                }
                else -> items(state.apps, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        // The mode decides which list the switch writes.
                        onSendsChanged = { sends -> onSendsChange(app.packageName, sends) },
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun WatchNotificationAppsScreenGrantedPreview() {
    OpenWatchTheme {
        WatchNotificationAppsScreen(
            state = WatchNotificationsUiState(
                enabled = true,
                accessGranted = true,
                disclosureAccepted = true,
                apps = listOf(
                    WatchNotificationApp(packageName = "org.example.messages", label = "Messages", sends = true),
                    WatchNotificationApp(packageName = "org.example.calendar", label = "Calendar", sends = true),
                    WatchNotificationApp(packageName = "org.example.news", label = "News", sends = false),
                ),
                loading = false,
            ),
            onEnabledChange = {},
            onOpenAccessSettings = {},
            onOnlyAllowedChange = {},
            onHideSensitiveChange = {},
            onSendsChange = { _, _ -> },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun WatchNotificationAppsScreenAccessMissingPreview() {
    OpenWatchTheme {
        WatchNotificationAppsScreen(
            state = WatchNotificationsUiState(
                enabled = true,
                accessGranted = false,
                disclosureAccepted = true,
                loading = false,
            ),
            onEnabledChange = {},
            onOpenAccessSettings = {},
            onOnlyAllowedChange = {},
            onHideSensitiveChange = {},
            onSendsChange = { _, _ -> },
        )
    }
}

@Composable
private fun MasterSwitchCard(
    state: WatchNotificationsUiState,
    onToggle: (Boolean) -> Unit,
    onOpenAccessSettings: () -> Unit,
) {
    OpenWatchCard {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            // The row toggles, so TalkBack reads the title with the state.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.toggleable(
                    value = state.active,
                    enabled = !state.loading,
                    role = Role.Switch,
                    onValueChange = onToggle,
                ),
            ) {
                Icon(
                    imageVector = Icons.Outlined.NotificationsActive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(Spacing.lg))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_watch_notifications_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.settings_watch_notifications_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(Spacing.lg))
                Switch(
                    checked = state.active,
                    onCheckedChange = null,
                    enabled = !state.loading,
                )
            }

            if (!state.accessGranted && !state.loading) {
                Spacer(modifier = Modifier.padding(top = Spacing.md))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.width(Spacing.lg))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_watch_notifications_grant),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(
                                R.string.settings_watch_notifications_grant_body,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    FilledTonalButton(onClick = onOpenAccessSettings) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = null,
                        )
                    }
                }
            }

            if (state.active) {
                Spacer(modifier = Modifier.padding(top = Spacing.sm))
                Text(
                    text = when {
                        state.onlyAllowed && state.sendingCount == 0 ->
                            stringResource(R.string.settings_watch_notifications_apps_allowed_none)
                        state.onlyAllowed -> pluralStringResource(
                            R.plurals.settings_watch_notifications_apps_allowed,
                            state.sendingCount,
                            state.sendingCount,
                        )
                        state.blockedCount == 0 -> stringResource(R.string.settings_watch_notifications_apps_none)
                        else -> pluralStringResource(
                            R.plurals.settings_watch_notifications_apps_blocked,
                            state.blockedCount,
                            state.blockedCount,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The two choices about what goes: which apps, and how much of a hidden channel. */
@Composable
private fun ForwardingOptionsCard(
    state: WatchNotificationsUiState,
    onOnlyAllowed: (Boolean) -> Unit,
    onHideSensitive: (Boolean) -> Unit,
) {
    OpenWatchCard(style = OpenWatchCardStyle.Neutral) {
        Column(modifier = Modifier.padding(vertical = Spacing.sm)) {
            SwitchRow(
                title = stringResource(R.string.settings_watch_notifications_only_allowed),
                body = stringResource(R.string.settings_watch_notifications_only_allowed_body),
                checked = state.onlyAllowed,
                onCheckedChange = onOnlyAllowed,
            )
            SwitchRow(
                title = stringResource(R.string.settings_watch_notifications_hide_sensitive),
                body = stringResource(R.string.settings_watch_notifications_hide_sensitive_body),
                checked = state.hideSensitiveContent,
                onCheckedChange = onHideSensitive,
            )
        }
    }
}

@Composable
private fun AppRow(
    app: WatchNotificationApp,
    onSendsChanged: (Boolean) -> Unit,
) {
    OpenWatchCard(style = OpenWatchCardStyle.Neutral) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = app.sends, role = Role.Switch, onValueChange = onSendsChanged)
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(Spacing.lg))
            // The row toggles; the switch only shows the state.
            Switch(
                checked = app.sends,
                onCheckedChange = null,
            )
        }
    }
}
