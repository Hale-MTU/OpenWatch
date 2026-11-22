package tech.mmarca.openwatch.features.scales

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Duration
import java.time.Instant
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.core.permissions.RuntimePermissions
import tech.mmarca.openwatch.devices.xiaomi.ScaleListenerStatus
import tech.mmarca.openwatch.ui.components.ConfirmRemoveDialog
import tech.mmarca.openwatch.ui.components.DeclareAppBar
import tech.mmarca.openwatch.ui.components.RenameDialog
import tech.mmarca.openwatch.ui.components.ScreenAppBar
import tech.mmarca.openwatch.ui.components.ScreenColumn
import tech.mmarca.openwatch.ui.components.ScreenErrorContent
import tech.mmarca.openwatch.ui.components.SectionHeader
import tech.mmarca.openwatch.ui.components.rememberHealthConnectPermissionLauncher
import tech.mmarca.openwatch.ui.components.rememberRuntimePermissionRequester
import tech.mmarca.openwatch.ui.theme.OpenWatchTheme
import tech.mmarca.openwatch.ui.theme.Spacing

/**
 * The scale's destination: the only place that knows the ViewModel. It declares the app bar,
 * re-reads everything on resume, owns the scan and Health Connect permission requests, opens
 * Bluetooth's settings and goes back once the scale is removed; [ScaleDeviceScreen] draws.
 */
@Composable
fun ScaleDeviceRoute(
    onOpenBodyProfile: () -> Unit,
    onRemoved: () -> Unit,
    viewModel: ScaleDeviceViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    DeclareAppBar(ScreenAppBar(title = state.name))

    // Grants change in Android's own screens, so everything is read again on the way back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val healthConnectLauncher = rememberHealthConnectPermissionLauncher(onResult = { viewModel.refresh() })
    val permissionRequester = rememberRuntimePermissionRequester { viewModel.refresh() }
    // Android 12 gave scanning its own grant. Before that a scan rides on location.
    val android12 = RuntimePermissions.hasBluetoothGrants()

    ScaleDeviceScreen(
        state = state,
        android12 = android12,
        onRename = viewModel::rename,
        onDeleteLastWeighIn = viewModel::deleteLastWeighIn,
        onGrantScanPermission = { permissionRequester.request(listOf(RuntimePermissions.bluetoothScanPermission())) },
        onOpenBluetoothSettings = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
        onAllowSystemWake = viewModel::allowSystemWake,
        onClaimIgnoredProfile = viewModel::claimIgnoredProfile,
        onGrantHealthConnect = { healthConnectLauncher.launch(state.missingWritePermissions) },
        onOpenBodyProfile = onOpenBodyProfile,
        onChangeKey = viewModel::openKeyDialog,
        onKeyEdited = viewModel::onKeyEdited,
        onSaveKey = viewModel::changeKey,
        onDismissKeyDialog = viewModel::dismissKeyDialog,
        onRemove = viewModel::remove,
    )

    LaunchedEffect(state.removed) {
        if (state.removed) onRemoved()
    }
}

/** One scale: whether it is heard, its last weigh-in, what Health Connect still lacks, and the scale itself. */
@Composable
fun ScaleDeviceScreen(
    state: ScaleDeviceUiState,
    /** Android 12 and later scan on their own grant; earlier ones on location. */
    android12: Boolean,
    onRename: (String) -> Unit,
    onDeleteLastWeighIn: () -> Unit,
    onGrantScanPermission: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onAllowSystemWake: () -> Unit,
    onClaimIgnoredProfile: () -> Unit,
    onGrantHealthConnect: () -> Unit,
    onOpenBodyProfile: () -> Unit,
    onChangeKey: () -> Unit,
    onKeyEdited: () -> Unit,
    onSaveKey: (String) -> Unit,
    onDismissKeyDialog: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showRemoveDialog by rememberSaveable { mutableStateOf(false) }

    val name = state.name
    if (name == null) {
        // Removed while this screen was open.
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_data))
        }
        return
    }

    ScreenColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalPadding = Spacing.lg,
    ) {
        ScaleStatusCard(state = state, onRename = { showRenameDialog = true })

        state.lastWeighIn?.let { weighIn ->
            SectionHeader(stringResource(R.string.settings_scales_last_title))
            ScaleLastWeighInCard(weighIn = weighIn, onDelete = onDeleteLastWeighIn)
        }
        state.screenError?.let { ScreenErrorContent(screenError = it) }

        if (state.needsListeningSection()) {
            SectionHeader(stringResource(R.string.settings_scales_section_listening))
            ScaleListeningCard(
                state = state,
                android12 = android12,
                onGrantScanPermission = onGrantScanPermission,
                onOpenBluetoothSettings = onOpenBluetoothSettings,
                onAllowSystemWake = onAllowSystemWake,
                onClaimIgnoredProfile = onClaimIgnoredProfile,
            )
        }

        if (state.needsHealthConnectSection()) {
            SectionHeader(stringResource(R.string.settings_health_connect_group_title))
            ScaleHealthConnectCard(
                state = state,
                onGrantHealthConnect = onGrantHealthConnect,
                onOpenBodyProfile = onOpenBodyProfile,
            )
        }

        SectionHeader(stringResource(R.string.settings_scales_section_scale))
        ScaleDetailsCards(state = state, onChangeKey = onChangeKey)
        ScaleRemoveCard(onRemove = { showRemoveDialog = true })
    }

    if (showRenameDialog) {
        RenameDialog(
            title = stringResource(R.string.settings_scales_rename),
            initialName = name,
            onSave = { newName ->
                showRenameDialog = false
                onRename(newName)
            },
            onDismiss = { showRenameDialog = false },
        )
    }
    if (state.keyDialogOpen) {
        ScaleKeyDialog(
            invalid = state.keyInputInvalid,
            onEdit = onKeyEdited,
            onSave = onSaveKey,
            onDismiss = onDismissKeyDialog,
        )
    }
    if (showRemoveDialog) {
        ConfirmRemoveDialog(
            title = stringResource(R.string.settings_device_remove_confirm_title, name),
            body = stringResource(R.string.settings_scales_remove_body),
            onConfirm = {
                showRemoveDialog = false
                onRemove()
            },
            onDismiss = { showRemoveDialog = false },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ScaleDeviceScreenPreview() {
    OpenWatchTheme {
        ScaleDeviceScreen(
            state = ScaleDeviceUiState(
                name = "Bathroom",
                address = "5C:E5:0C:4A:91:2F",
                profile = 1,
                listenerStatus = ScaleListenerStatus.LISTENING,
                wokenBySystem = true,
                lastWeighIn = ScaleWeighInDisplay(
                    time = Instant.now().minus(Duration.ofHours(3)),
                    weight = "72.4 kg",
                    heartRate = "68 bpm",
                    bodyFat = "18.6 %",
                    leanMass = "58.9 kg",
                    bodyWater = "42.7 kg",
                    weightOnly = false,
                ),
            ),
            android12 = true,
            onRename = {},
            onDeleteLastWeighIn = {},
            onGrantScanPermission = {},
            onOpenBluetoothSettings = {},
            onAllowSystemWake = {},
            onClaimIgnoredProfile = {},
            onGrantHealthConnect = {},
            onOpenBodyProfile = {},
            onChangeKey = {},
            onKeyEdited = {},
            onSaveKey = {},
            onDismissKeyDialog = {},
            onRemove = {},
        )
    }
}

/** The Listening section shows only what stands in the way. */
private fun ScaleDeviceUiState.needsListeningSection(): Boolean =
    listenerStatus != ScaleListenerStatus.LISTENING || !wokenBySystem || otherProfileIgnored

private fun ScaleDeviceUiState.needsHealthConnectSection(): Boolean =
    hasPendingWeighIns || missingWritePermissions.isNotEmpty() || missingProfileInputs.isNotEmpty()
