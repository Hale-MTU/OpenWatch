package tech.mmarca.openwatch.features.scales

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Duration
import java.time.Instant
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.devices.xiaomi.ScaleListenerStatus
import tech.mmarca.openwatch.domain.model.OsPermissionRow
import tech.mmarca.openwatch.ui.components.DeviceListRow
import tech.mmarca.openwatch.ui.components.OpenWatchButton
import tech.mmarca.openwatch.ui.components.OpenWatchCard
import tech.mmarca.openwatch.ui.components.OpenWatchTextButton
import tech.mmarca.openwatch.ui.components.OsPermissionsDialog
import tech.mmarca.openwatch.ui.components.formatDeviceTime
import tech.mmarca.openwatch.ui.theme.OpenWatchTheme
import tech.mmarca.openwatch.ui.theme.Spacing

/**
 * The scale's section: the only place that knows the ViewModel. It re-reads the grants on
 * every resume, owns the permission request and opens the supported-scales page; [ScalesSection] draws.
 */
@Composable
fun ScalesSectionRoute(
    onOpenScale: () -> Unit,
    viewModel: ScalesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val supportedScalesUrl = stringResource(R.string.settings_scales_supported_url)
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refreshOsPermissions() }

    // Grants change in Android's own screens, so they are re-read on the way back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    ScalesSection(
        state = state,
        onOpenScale = onOpenScale,
        onAdd = viewModel::startAdd,
        onOpenSupportedScales = { context.startActivity(Intent(Intent.ACTION_VIEW, supportedScalesUrl.toUri())) },
        onGrantAllPermissions = { permissionLauncher.launch(state.osPermissions.requestablePermissions.toTypedArray()) },
        // The scale's rows are all runtime grants: no settings screen to walk.
        onGrantPermissionRow = { row -> permissionLauncher.launch(row.permissions.toTypedArray()) },
        onContinueToAdd = viewModel::openAddFlow,
        onDismissPermissionsGate = viewModel::dismissPermissionsGate,
        onFind = viewModel::findScale,
        onNameChange = viewModel::onNameInputChange,
        onKeyChange = viewModel::onKeyInputChange,
        onSave = viewModel::saveScale,
        onCloseAddFlow = viewModel::closeAddFlow,
    )
}

/** The scale's card, or the way to add one. Everything about the scale lives in its device view. */
@Composable
fun ScalesSection(
    state: ScalesUiState,
    onOpenScale: () -> Unit,
    onAdd: () -> Unit,
    /** The supported-scales page, which also says where each scale's key is found. */
    onOpenSupportedScales: () -> Unit,
    onGrantAllPermissions: () -> Unit,
    onGrantPermissionRow: (OsPermissionRow) -> Unit,
    onContinueToAdd: () -> Unit,
    onDismissPermissionsGate: () -> Unit,
    onFind: () -> Unit,
    onNameChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    onCloseAddFlow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.settings_scales_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.lg),
        )

        val scale = state.scale
        if (scale == null) {
            NoScaleCard(
                onAdd = onAdd,
                onOpenSupportedScales = onOpenSupportedScales,
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
        } else {
            ScaleRow(scale = scale, onOpen = onOpenScale, modifier = Modifier.padding(horizontal = Spacing.lg))
        }
    }

    if (state.showPermissionsGate) {
        OsPermissionsDialog(
            catalog = state.osPermissions,
            title = stringResource(R.string.settings_scales_permissions_title),
            body = stringResource(R.string.settings_scales_permissions_body),
            continueLabel = stringResource(R.string.settings_scales_add),
            onGrantAll = onGrantAllPermissions,
            onGrantRow = onGrantPermissionRow,
            onContinue = onContinueToAdd,
            onDismiss = onDismissPermissionsGate,
        )
    }

    if (state.showAddFlow) {
        AddScaleDialog(
            state = state,
            onFind = onFind,
            onNameChange = onNameChange,
            onKeyChange = onKeyChange,
            onOpenKeyHelp = onOpenSupportedScales,
            onSave = onSave,
            onClose = onCloseAddFlow,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ScalesSectionPreview() {
    OpenWatchTheme {
        ScalesSection(
            state = ScalesUiState(
                scale = ScaleCardState(
                    name = "Bathroom",
                    status = ScaleListenerStatus.LISTENING,
                    wokenBySystem = true,
                    lastWeighInAt = Instant.now().minus(Duration.ofHours(3)),
                ),
            ),
            onOpenScale = {},
            onAdd = {},
            onOpenSupportedScales = {},
            onGrantAllPermissions = {},
            onGrantPermissionRow = {},
            onContinueToAdd = {},
            onDismissPermissionsGate = {},
            onFind = {},
            onNameChange = {},
            onKeyChange = {},
            onSave = {},
            onCloseAddFlow = {},
        )
    }
}

@Composable
private fun NoScaleCard(onAdd: () -> Unit, onOpenSupportedScales: () -> Unit, modifier: Modifier = Modifier) {
    OpenWatchCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = stringResource(R.string.settings_scales_empty_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.settings_scales_empty_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The list lives on the site: it grows without an app update.
            OpenWatchTextButton(onClick = onOpenSupportedScales) {
                Text(stringResource(R.string.settings_scales_supported_action))
            }
            OpenWatchButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    modifier = Modifier.size(AddIconSize),
                )
                Text(
                    text = stringResource(R.string.settings_scales_add),
                    modifier = Modifier.padding(start = Spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun ScaleRow(scale: ScaleCardState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    DeviceListRow(
        name = scale.name,
        status = stringResource(scale.statusRes()),
        detail = scale.lastWeighInAt?.let { stringResource(R.string.settings_scales_last_weigh_in_at, formatDeviceTime(it)) }
            ?: stringResource(R.string.settings_scales_no_weigh_in),
        onOpen = onOpen,
        modifier = modifier,
        icon = Icons.Outlined.MonitorWeight,
    )
}

/** One line on what the scale is doing, for the card and the device header. */
internal fun ScaleCardState.statusRes(): Int = scaleStatusRes(status, wokenBySystem)

internal fun scaleStatusRes(status: ScaleListenerStatus, wokenBySystem: Boolean): Int = when (status) {
    ScaleListenerStatus.LISTENING ->
        if (wokenBySystem) R.string.settings_scales_listening else R.string.settings_scales_listening_while_open

    ScaleListenerStatus.BLUETOOTH_OFF -> R.string.settings_scales_bluetooth_off
    ScaleListenerStatus.PERMISSION_MISSING -> R.string.settings_scales_not_listening
    ScaleListenerStatus.UNAVAILABLE -> R.string.settings_scales_unavailable
    ScaleListenerStatus.OFF -> R.string.settings_scales_not_listening
}

private val AddIconSize = 18.dp

/** Gap between a row's leading icon and its text. */
