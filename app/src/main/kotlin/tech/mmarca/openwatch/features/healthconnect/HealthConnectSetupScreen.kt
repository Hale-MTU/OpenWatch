package tech.mmarca.openwatch.features.healthconnect

import android.content.Intent
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.mmarca.openwatch.PermissionsRationaleActivity
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.domain.model.HealthConnectAvailability
import tech.mmarca.openwatch.healthconnect.openHealthConnectPermissionSettings
import tech.mmarca.openwatch.ui.components.OpenWatchButton
import tech.mmarca.openwatch.ui.components.OpenWatchCard
import tech.mmarca.openwatch.ui.components.OpenWatchOutlinedButton
import tech.mmarca.openwatch.ui.components.OpenWatchTextButton
import tech.mmarca.openwatch.ui.components.ScreenErrorContent
import tech.mmarca.openwatch.ui.components.SwitchCard
import tech.mmarca.openwatch.ui.components.rememberHealthConnectPermissionLauncher
import tech.mmarca.openwatch.ui.theme.Spacing

/** Where the app's Health Connect access is set up: availability, the grants, the sync switch. */
@Composable
fun HealthConnectSetupScreen(
    viewModel: HealthConnectSetupViewModel,
    onContinue: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Grants change in Health Connect's own screen, so they are re-read on the way back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val permissionLauncher = rememberHealthConnectPermissionLauncher(onResult = viewModel::refresh)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            text = stringResource(R.string.hc_setup_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OpenWatchCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                // The grants could not be read: say so, rather than "0 of 0 granted".
                if (state.error != null) {
                    Text(text = stringResource(R.string.hc_setup_permissions_title), style = MaterialTheme.typography.titleSmall)
                    ScreenErrorContent(screenError = state.error)
                    return@Column
                }
                val statusRes = when (state.availability) {
                    HealthConnectAvailability.AVAILABLE ->
                        if (state.allGranted) R.string.hc_setup_status_granted else R.string.hc_setup_status_partial
                    HealthConnectAvailability.NEEDS_PROVIDER_UPDATE -> R.string.hc_setup_status_update
                    HealthConnectAvailability.NEEDS_PLAY_STORE -> R.string.hc_setup_status_play_store
                    HealthConnectAvailability.NOT_SUPPORTED -> R.string.hc_setup_status_unavailable
                }
                Text(text = stringResource(R.string.hc_setup_permissions_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (state.availability == HealthConnectAvailability.AVAILABLE) {
                        stringResource(statusRes, state.grantedCount, state.declaredCount)
                    } else {
                        stringResource(statusRes)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.availability == HealthConnectAvailability.AVAILABLE && state.missing.isNotEmpty()) {
                    Text(text = stringResource(R.string.hc_setup_missing_title), style = MaterialTheme.typography.labelLarge)
                    state.missing.forEach { name ->
                        Text(text = "• $name", style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.missingExtraAccess) {
                        Text(
                            text = stringResource(R.string.hc_setup_missing_extra),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.availability == HealthConnectAvailability.AVAILABLE) {
                    if (!state.allGranted) {
                        OpenWatchButton(
                            onClick = { permissionLauncher.launch(viewModel.requestablePermissions) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.hc_setup_grant))
                        }
                    }
                    OpenWatchOutlinedButton(
                        onClick = { openHealthConnectPermissionSettings(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.hc_setup_open_settings))
                    }
                }
                // What each type is for, before the user is asked for it. The same screen
                // Health Connect opens from its own permission page.
                OpenWatchTextButton(
                    onClick = { context.startActivity(Intent(context, PermissionsRationaleActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.health_data_title))
                }
            }
        }

        SwitchCard(
            title = stringResource(R.string.hc_setup_sync_title),
            body = stringResource(R.string.hc_setup_sync_body),
            checked = state.syncEnabled,
            onCheckedChange = viewModel::setSyncEnabled,
            modifier = Modifier.fillMaxWidth(),
        )

        OpenWatchButton(
            onClick = onContinue,
            enabled = state.availability == HealthConnectAvailability.AVAILABLE,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.hc_setup_continue))
        }
    }
}
