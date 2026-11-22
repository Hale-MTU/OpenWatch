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
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.mmarca.openwatch.PermissionsRationaleActivity
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.core.presentation.ScreenError
import tech.mmarca.openwatch.domain.model.HealthConnectAvailability
import tech.mmarca.openwatch.healthconnect.openHealthConnectPermissionSettings
import tech.mmarca.openwatch.ui.components.OpenWatchButton
import tech.mmarca.openwatch.ui.components.OpenWatchCard
import tech.mmarca.openwatch.ui.components.OpenWatchOutlinedButton
import tech.mmarca.openwatch.ui.components.OpenWatchTextButton
import tech.mmarca.openwatch.ui.components.ScreenErrorContent
import tech.mmarca.openwatch.ui.components.SwitchCard
import tech.mmarca.openwatch.ui.components.rememberHealthConnectPermissionLauncher
import tech.mmarca.openwatch.ui.theme.OpenWatchTheme
import tech.mmarca.openwatch.ui.theme.Spacing

/**
 * The Health Connect setup destination: the only place that knows the ViewModel. It owns the
 * permission dialog and opens Health Connect's page and the health data screen;
 * [HealthConnectSetupScreen] draws.
 */
@Composable
fun HealthConnectSetupRoute(
    /** Continue to Home, during onboarding; null on the page Settings and Home open. */
    onContinue: (() -> Unit)?,
    viewModel: HealthConnectSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Grants change in Health Connect's own screen, so they are re-read on the way back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val permissionLauncher = rememberHealthConnectPermissionLauncher(onResult = viewModel::refresh)

    HealthConnectSetupScreen(
        state = state,
        onContinue = onContinue,
        onGrantPermissions = { permissionLauncher.launch(viewModel.requestablePermissions) },
        onOpenHealthConnectSettings = { openHealthConnectPermissionSettings(context) },
        onOpenHealthData = { context.startActivity(Intent(context, PermissionsRationaleActivity::class.java)) },
        onSyncEnabledChange = viewModel::setSyncEnabled,
    )
}

/** Where the app's Health Connect access is set up: availability, the grants, the sync switch. */
@Composable
fun HealthConnectSetupScreen(
    state: HealthConnectSetupUiState,
    /** Continue to Home, during onboarding; null hides the button. */
    onContinue: (() -> Unit)?,
    onGrantPermissions: () -> Unit,
    onOpenHealthConnectSettings: () -> Unit,
    onOpenHealthData: () -> Unit,
    onSyncEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
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
                        if (state.allGranted) R.string.hc_setup_status_all_granted else R.string.hc_setup_status_partial
                    HealthConnectAvailability.NEEDS_PROVIDER_UPDATE -> R.string.hc_setup_status_update
                    HealthConnectAvailability.NEEDS_PLAY_STORE -> R.string.hc_setup_status_play_store
                    HealthConnectAvailability.NOT_SUPPORTED -> R.string.hc_setup_status_unavailable
                }
                Text(text = stringResource(R.string.hc_setup_permissions_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (state.availability == HealthConnectAvailability.AVAILABLE && state.allGranted) {
                        stringResource(statusRes, state.declaredCount)
                    } else if (state.availability == HealthConnectAvailability.AVAILABLE) {
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
                            onClick = onGrantPermissions,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.hc_setup_grant))
                        }
                    }
                    OpenWatchOutlinedButton(
                        onClick = onOpenHealthConnectSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.hc_setup_open_settings))
                    }
                }
                // What each type is for, before the user is asked for it. The same screen
                // Health Connect opens from its own permission page.
                OpenWatchTextButton(
                    onClick = onOpenHealthData,
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
            onCheckedChange = onSyncEnabledChange,
            modifier = Modifier.fillMaxWidth(),
        )

        if (onContinue != null) {
            OpenWatchButton(
                onClick = onContinue,
                enabled = state.availability == HealthConnectAvailability.AVAILABLE,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.hc_setup_continue))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HealthConnectSetupScreenPreview() {
    OpenWatchTheme {
        HealthConnectSetupScreen(
            state = HealthConnectSetupUiState(
                availability = HealthConnectAvailability.AVAILABLE,
                grantedCount = 29,
                declaredCount = 31,
                missing = listOf("Read heart rate", "Write steps"),
                missingExtraAccess = true,
                syncEnabled = true,
                isLoading = false,
            ),
            onContinue = {},
            onGrantPermissions = {},
            onOpenHealthConnectSettings = {},
            onOpenHealthData = {},
            onSyncEnabledChange = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HealthConnectSetupScreenErrorPreview() {
    OpenWatchTheme {
        HealthConnectSetupScreen(
            state = HealthConnectSetupUiState(
                availability = HealthConnectAvailability.AVAILABLE,
                syncEnabled = true,
                isLoading = false,
                error = ScreenError.Text(R.string.screen_error_generic),
            ),
            onContinue = null,
            onGrantPermissions = {},
            onOpenHealthConnectSettings = {},
            onOpenHealthData = {},
            onSyncEnabledChange = {},
        )
    }
}
