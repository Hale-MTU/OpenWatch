package tech.mmarca.openwatch.wear.features.status

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import tech.mmarca.openwatch.wear.BedtimeHold
import tech.mmarca.openwatch.wear.PendingPhone
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.SyncRetention
import tech.mmarca.openwatch.wear.WearLinkUiState
import tech.mmarca.openwatch.wear.ui.preview.WearPreviews
import tech.mmarca.openwatch.wear.ui.theme.OpenWatchWearTheme

/** What the status screen's buttons do outside its settings. */
internal class StatusActions(
    val onGrant: () -> Unit,
    val onAllow: (PendingPhone) -> Unit,
    val onBlock: (PendingPhone) -> Unit,
    val onOpenBluetoothSettings: () -> Unit,
    val onTogglePpgLog: () -> Unit,
)

/**
 * The status screen: what the watch records, whether the phone can reach
 * it, and the phones that may connect; the recording settings are Settings'
 * own screen (`RecordingSettingsScreen`). Its
 * [StatusViewModel] reads the store off the main thread while the screen is
 * shown, and again each time it comes back, which also catches a grant made
 * in the system settings.
 */
@Composable
internal fun StatusScreen(
    onGrant: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel = viewModel { StatusViewModel.create(context) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val actions = StatusActions(
        onGrant = onGrant,
        onAllow = viewModel::allow,
        onBlock = viewModel::block,
        onOpenBluetoothSettings = onOpenBluetoothSettings,
        onTogglePpgLog = viewModel::togglePpgLog,
    )
    StatusContent(state, actions)
}

/** The status screen for [state]; stateless, so a preview draws it too. */
@Composable
internal fun StatusContent(state: StatusUiState, actions: StatusActions) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val readings = state.readings
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { Header(ppgLogAvailable = readings?.ppgLogAvailable == true, onTogglePpgLog = actions.onTogglePpgLog) }
            if (readings != null) recordingItems(readings, state.link)
            item { StatusLine(stringResource(linkLine(readings?.bluetoothGranted, state.link))) }
            if (readings != null) syncItems(readings)
            if (state.needsGrant) statusButton(spec, R.string.action_grant, onClick = actions.onGrant)
            readings?.samsungDiagnostics?.let { on ->
                item { StatusLine(stringResource(if (on) R.string.status_samsung_diagnostics_on else R.string.status_samsung_diagnostics_off)) }
            }


            phoneItems(state.link, spec, actions.onAllow, actions.onBlock, actions.onOpenBluetoothSettings)
            if (state.link.ppgLogging) item { StatusLine(stringResource(R.string.status_ppg_logging)) }
        }
    }
}

/** The app's mark; in a debuggable build with the sensor, a long press starts or stops the raw PPG log. */
@Composable
private fun Header(ppgLogAvailable: Boolean, onTogglePpgLog: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(R.drawable.open_watch_launcher_prod),
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .pointerInput(ppgLogAvailable) {
                    if (ppgLogAvailable) detectTapGestures(onLongPress = { onTogglePpgLog() })
                },
        )
    }
}

/** The heart rate, what the store holds, and the sleep hold. */
private fun TransformingLazyColumnScope.recordingItems(readings: StatusReadings, link: WearLinkUiState) {
    item {
        val bpm = readings.latestBpm
        StatusLine(
            text = when {
                !readings.heartRateGranted -> stringResource(R.string.status_heart_rate_not_granted)
                bpm == null -> stringResource(R.string.status_heart_rate_waiting)
                else -> stringResource(R.string.status_heart_rate_bpm, bpm)
            },
            title = true,
        )
    }
    item { StatusLine(stringResource(R.string.status_samples_stored, readings.heartRateSamples)) }
    item { StatusLine(stringResource(R.string.status_minutes_stored, readings.sleepMinutes)) }
    item {
        StatusLine(
            when {
                !readings.hasStepCounter -> stringResource(R.string.status_steps_no_sensor)
                !readings.stepsGranted -> stringResource(R.string.status_steps_not_granted)
                else -> stringResource(R.string.status_step_minutes_stored, readings.stepMinutes)
            },
        )
    }
    item {
        StatusLine(
            stringResource(
                when (link.bedtimeHold) {
                    BedtimeHold.OFF -> if (link.sleepHoldActive) R.string.status_sleep_holding else R.string.status_bedtime_off
                    BedtimeHold.HOLDING -> R.string.status_bedtime_holding
                    BedtimeHold.PAUSED_OFF_WRIST -> R.string.status_bedtime_paused_off_wrist
                    BedtimeHold.PAUSED_CHARGING -> R.string.status_bedtime_paused_charging
                },
            ),
        )
    }
}

/** When a phone last pulled, and the warning before the watch drops what no phone pulled. */
private fun TransformingLazyColumnScope.syncItems(readings: StatusReadings) {
    item {
        val last = readings.lastPulledMillis
        StatusLine(
            if (last == null) {
                stringResource(R.string.status_never_synced)
            } else {
                // The platform's own wording, in the watch's language: "3 hours ago", "yesterday".
                val ago = DateUtils.getRelativeTimeSpanString(last, maxOf(last, readings.readAtMillis), DateUtils.MINUTE_IN_MILLIS)
                stringResource(R.string.status_last_synced, ago)
            },
        )
    }
    val warning = readings.syncWarning ?: return
    item {
        val text = when (warning.level) {
            SyncRetention.Level.SOON -> warning.daysLeft(readings.readAtMillis).let { days ->
                pluralStringResource(R.plurals.status_sync_soon, days, days)
            }
            SyncRetention.Level.PRUNING -> stringResource(R.string.status_sync_pruning)
        }
        StatusLine(text, color = MaterialTheme.colorScheme.error)
    }
}

/** What the phone link is doing, here and on the settings' phone screen; [bluetoothGranted] is null until read. */
@StringRes
internal fun linkLine(bluetoothGranted: Boolean?, link: WearLinkUiState): Int = when {
    bluetoothGranted == false -> R.string.status_link_not_granted
    !link.bluetoothOn -> R.string.status_link_bluetooth_off
    link.listening -> R.string.status_link_listening
    else -> R.string.status_link_starting
}

/** One full-width button of the status list, shaped by its place on the round screen. */
internal fun TransformingLazyColumnScope.statusButton(spec: TransformationSpec, @StringRes label: Int, onClick: () -> Unit) {
    item {
        Button(
            onClick = onClick,
            label = { Text(stringResource(label)) },
            colors = ButtonDefaults.filledTonalButtonColors(),
            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
            transformation = SurfaceTransformation(spec),
        )
    }
}

/** One centred line of the status list; [title] for the headline. */
@Composable
internal fun StatusLine(text: String, title: Boolean = false, color: Color = Color.Unspecified) {
    Text(
        text = text,
        color = color,
        style = if (title) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

@WearPreviews
@Composable
private fun StatusContentPreview() {
    OpenWatchWearTheme {
        AppScaffold {
            StatusContent(
                state = StatusUiState(
                    readings = StatusReadings(
                        heartRateGranted = true,
                        bluetoothGranted = true,
                        stepsGranted = true,
                        hasStepCounter = true,
                        latestBpm = 64,
                        heartRateSamples = 4_210,
                        sleepMinutes = 1_380,
                        stepMinutes = 610,
                        samsungDiagnostics = null,
                        samsungPlatform = false,
                        ppgLogAvailable = false,
                        readAtMillis = 1_700_000_000_000,
                        lastPulledMillis = 1_700_000_000_000 - 3 * 60 * 60 * 1000,
                    ),
                    link = WearLinkUiState(listening = true),
                ),
                actions = StatusActions({}, {}, {}, {}, {}),
            )
        }
    }
}
