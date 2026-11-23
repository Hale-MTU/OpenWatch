package tech.mmarca.openwatch.features.watches

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.time.Duration
import tech.mmarca.openwatch.core.ui.R as UiR
import tech.mmarca.openwatch.features.watches.garmin.R
import tech.mmarca.openwatch.core.presentation.ScreenError
import tech.mmarca.openwatch.core.presentation.resolve
import tech.mmarca.openwatch.devices.garmin.GarminEntryKind
import tech.mmarca.openwatch.devices.garmin.GarminSettingsEntry
import tech.mmarca.openwatch.devices.garmin.GarminSettingsOption
import tech.mmarca.openwatch.devices.garmin.GarminSettingsScreen
import tech.mmarca.openwatch.ui.components.OpenWatchCard
import tech.mmarca.openwatch.ui.components.screenContent
import tech.mmarca.openwatch.ui.theme.OpenWatchTheme
import tech.mmarca.openwatch.ui.theme.Spacing

/**
 * The watch-settings destination: the only place that knows the ViewModel. It closes the
 * screen after a delete, re-reads on resume and names the watch a subscreen opens on;
 * [WatchSettingsScreen] draws.
 */
@Composable
fun WatchSettingsRoute(
    onOpenSubscreen: (deviceId: String, screenId: Int) -> Unit,
    onClose: () -> Unit,
    viewModel: WatchSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.closeScreen) {
        if (state.closeScreen) {
            viewModel.onCloseHandled()
            onClose()
        }
    }

    // Walking back out of a subscreen re-reads this one.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onResumed()
    }

    WatchSettingsScreen(
        state = state,
        onRetry = viewModel::refresh,
        onOpenSubscreen = { subId -> onOpenSubscreen(viewModel.deviceId, subId) },
        onToggle = viewModel::setSwitch,
        onChooseOption = viewModel::chooseOption,
        onPickTime = viewModel::setTime,
        onRunAction = viewModel::runAction,
    )
}

/**
 * One screen of the watch's own settings, rendered from what the watch
 * sent. The watch's title renders as a heading inside the content, and
 * change results show as inline notices beside the rows they changed.
 */
@Composable
fun WatchSettingsScreen(
    state: WatchSettingsUiState,
    onRetry: () -> Unit,
    onOpenSubscreen: (screenId: Int) -> Unit,
    onToggle: (entryId: Int, value: Boolean) -> Unit,
    onChooseOption: (entryId: Int, index: Int) -> Unit,
    onPickTime: (entryId: Int, hour: Int, minute: Int) -> Unit,
    onRunAction: (entryId: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.isLoading -> Message(
            text = stringResource(R.string.settings_watch_settings_reading),
            busy = true,
            modifier = modifier,
        )

        state.error != null -> Message(
            text = state.error.resolve() ?: stringResource(R.string.settings_watch_settings_unreachable),
            retryLabel = stringResource(R.string.settings_watch_settings_retry),
            onRetry = onRetry,
            modifier = modifier,
        )

        state.isEmpty -> Message(
            text = stringResource(R.string.settings_watch_settings_empty),
            retryLabel = stringResource(R.string.settings_watch_settings_retry),
            onRetry = onRetry,
            modifier = modifier,
        )

        else -> {
            val screen = state.screen ?: return
            // Blank rows are dropped: an alarm list reserves one per slot.
            val rows = screen.entries.filterNot { it.isBlank }
            LazyColumn(
                modifier = modifier.screenContent(),
                contentPadding = PaddingValues(
                    top = Spacing.sm,
                    bottom = Spacing.xxl,
                ),
            ) {
                item(key = "header") {
                    ScreenHeader(
                        watchTitle = screen.title,
                        hasState = screen.hasState,
                        notice = state.notice,
                    )
                }
                items(rows, key = { it.id }) { entry ->
                    EntryRow(
                        entry = entry,
                        busy = entry.id in state.busyEntryIds,
                        onToggle = { value -> onToggle(entry.id, value) },
                        onOpenSubscreen = onOpenSubscreen,
                        onChooseOption = { index -> onChooseOption(entry.id, index) },
                        onPickTime = { hour, minute ->
                            onPickTime(entry.id, hour, minute)
                        },
                        onRunAction = { onRunAction(entry.id) },
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun WatchSettingsScreenPreview() {
    OpenWatchTheme {
        WatchSettingsScreen(
            state = WatchSettingsUiState(
                isLoading = false,
                screen = GarminSettingsScreen(
                    screenId = 1,
                    title = "System",
                    entries = listOf(
                        GarminSettingsEntry(
                            id = 1,
                            kind = GarminEntryKind.TOGGLE,
                            title = "Auto lock",
                            switchedOn = true,
                        ),
                        GarminSettingsEntry(
                            id = 2,
                            kind = GarminEntryKind.OPTIONS,
                            title = "Backlight",
                            summary = "Auto",
                            options = listOf(GarminSettingsOption(0, "Auto"), GarminSettingsOption(1, "Always")),
                            selectedIndex = 0,
                        ),
                        // A change in flight: drawn busy.
                        GarminSettingsEntry(
                            id = 3,
                            kind = GarminEntryKind.TOGGLE,
                            title = "Do not disturb",
                            switchedOn = false,
                        ),
                    ),
                ),
                busyEntryIds = setOf(3),
            ),
            onRetry = {},
            onOpenSubscreen = {},
            onToggle = { _, _ -> },
            onChooseOption = { _, _ -> },
            onPickTime = { _, _, _ -> },
            onRunAction = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun WatchSettingsScreenUnreachablePreview() {
    OpenWatchTheme {
        WatchSettingsScreen(
            state = WatchSettingsUiState(
                isLoading = false,
                error = ScreenError.Text(R.string.settings_watch_settings_unreachable),
            ),
            onRetry = {},
            onOpenSubscreen = {},
            onToggle = { _, _ -> },
            onChooseOption = { _, _ -> },
            onPickTime = { _, _, _ -> },
            onRunAction = {},
        )
    }
}

@Composable
private fun ScreenHeader(
    watchTitle: String?,
    hasState: Boolean,
    notice: WatchSettingsNotice?,
) {
    Column(modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.md)) {
        if (!watchTitle.isNullOrBlank()) {
            // The watch's own name for this screen.
            Text(
                text = watchTitle,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = Spacing.xs),
            )
        }
        Text(
            text = stringResource(R.string.settings_watch_settings_from_watch),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hasState) {
            Text(
                text = stringResource(R.string.settings_watch_settings_no_state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = Spacing.md),
            )
        }
        notice?.let {
            Text(
                text = when (it) {
                    WatchSettingsNotice.REFUSED ->
                        stringResource(R.string.settings_watch_settings_refused)

                    WatchSettingsNotice.UNANSWERED ->
                        stringResource(R.string.settings_watch_settings_unanswered)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = Spacing.md),
            )
        }
    }
}

@Composable
private fun EntryRow(
    entry: GarminSettingsEntry,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenSubscreen: (Int) -> Unit,
    onChooseOption: (Int) -> Unit,
    onPickTime: (hour: Int, minute: Int) -> Unit,
    onRunAction: () -> Unit,
) {
    var showOptions by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showConfirmAction by remember { mutableStateOf(false) }

    val title = entry.title.orEmpty()

    when (entry.kind) {
        GarminEntryKind.TOGGLE -> RowCard(
            title = title,
            summary = entry.summary,
            enabled = !busy,
            // Disabled while in flight, not optimistic: the value shown is the watch's.
            onClick = if (busy) null else ({ onToggle(!(entry.switchedOn ?: false)) }),
            checked = entry.switchedOn ?: false,
            trailing = {
                if (busy) {
                    RowSpinner()
                } else {
                    // The row toggles; the switch only shows the state.
                    Switch(checked = entry.switchedOn ?: false, onCheckedChange = null)
                }
            },
        )

        GarminEntryKind.SUBSCREEN -> RowCard(
            title = title,
            summary = entry.summary,
            enabled = true,
            onClick = { entry.subscreenId?.let(onOpenSubscreen) },
            trailing = { Chevron() },
        )

        GarminEntryKind.OPTIONS -> {
            // Only offered when the watch sent choices.
            val canChoose = entry.options.isNotEmpty() && !busy
            RowCard(
                title = title,
                summary = entry.summary,
                enabled = canChoose,
                onClick = if (canChoose) ({ showOptions = true }) else null,
                trailing = { if (busy) RowSpinner() else Chevron() },
            )
        }

        GarminEntryKind.TIME -> RowCard(
            title = title,
            summary = entry.summary,
            enabled = !busy,
            onClick = if (busy) null else ({ showTimePicker = true }),
            trailing = {
                if (busy) {
                    RowSpinner()
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )

        GarminEntryKind.NUMBER -> {
            // The watch bounds these itself and does not send the bounds. Read-only until known.
            val value = listOfNotNull(
                entry.summary?.takeIf { it.isNotEmpty() },
                entry.unit?.takeIf { it.isNotEmpty() },
            ).joinToString(" ")
            RowCard(
                title = title,
                summary = value.ifEmpty { null },
                enabled = false,
                onClick = null,
            )
        }

        GarminEntryKind.ACTION -> RowCard(
            // A button the watch put here. Asked first: there is no undo.
            title = title,
            summary = null,
            titleColor = MaterialTheme.colorScheme.error,
            enabled = !busy,
            onClick = if (busy) null else ({ showConfirmAction = true }),
            trailing = { if (busy) RowSpinner() },
        )

        GarminEntryKind.INERT -> RowCard(
            // On the watch, not actionable from a phone. Dimmed, not dropped.
            title = title,
            summary = entry.summary,
            titleColor = MaterialTheme.colorScheme.onSurfaceVariant,
            enabled = false,
            onClick = null,
        )
    }

    if (showOptions) {
        OptionsDialog(
            entry = entry,
            onDismiss = { showOptions = false },
            onChosen = { index ->
                showOptions = false
                onChooseOption(index)
            },
        )
    }
    if (showTimePicker) {
        EntryTimePickerDialog(
            title = entry.title,
            current = entry.time,
            onDismiss = { showTimePicker = false },
            onConfirm = { hour, minute ->
                showTimePicker = false
                onPickTime(hour, minute)
            },
        )
    }
    if (showConfirmAction) {
        ConfirmActionDialog(
            title = title,
            onDismiss = { showConfirmAction = false },
            onConfirm = {
                showConfirmAction = false
                onRunAction()
            },
        )
    }
}

@Composable
private fun RowCard(
    title: String,
    summary: String?,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    titleColor: Color = Color.Unspecified,
    trailing: (@Composable () -> Unit)? = null,
    // Set on a switch row: [onClick] flips it, and TalkBack reads the title with the state.
    checked: Boolean? = null,
) {
    OpenWatchCard(
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .let { base ->
                    when {
                        checked != null -> base.toggleable(
                            value = checked,
                            enabled = onClick != null && enabled,
                            role = Role.Switch,
                            onValueChange = { onClick?.invoke() },
                        )
                        onClick != null && enabled -> base.clickable(onClick = onClick)
                        else -> base
                    }
                }
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = titleColor,
                )
                if (!summary.isNullOrEmpty()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (trailing != null) {
                Spacer(modifier = Modifier.width(Spacing.md))
                trailing()
            }
        }
    }
}

@Composable
private fun RowSpinner() {
    CircularProgressIndicator(
        modifier = Modifier.size(Spacing.xl),
        strokeWidth = WatchProgressStroke,
    )
}

@Composable
private fun Chevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Offers the options the WATCH sent, marking the chosen one by POSITION. */
@Composable
private fun OptionsDialog(
    entry: GarminSettingsEntry,
    onDismiss: () -> Unit,
    onChosen: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.title.orEmpty()) },
        text = {
            Column {
                for (option in entry.options) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChosen(option.index) }
                            .padding(vertical = WatchSettingRowPadding),
                    ) {
                        // By position, which is what the watch reports.
                        Icon(
                            imageVector = if (option.index == entry.selectedIndex) {
                                Icons.Outlined.RadioButtonChecked
                            } else {
                                Icons.Outlined.RadioButtonUnchecked
                            },
                            contentDescription = null,
                            modifier = Modifier.size(Spacing.xl),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(Spacing.md))
                        Text(
                            text = option.title,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(UiR.string.action_cancel))
            }
        },
    )
}

/** Opened at the watch's own time, so a nudge does not reset the alarm to now. */
@Composable
private fun EntryTimePickerDialog(
    title: String?,
    current: Duration?,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit,
) {
    val context = LocalContext.current
    val initialMinutes = current?.inWholeMinutes
    val timePickerState = rememberTimePickerState(
        initialHour = (initialMinutes?.let { it / 60 % 24 } ?: 0L).toInt(),
        initialMinute = (initialMinutes?.let { it % 60 } ?: 0L).toInt(),
        is24Hour = DateFormat.is24HourFormat(context),
    )
    TimePickerDialog(
        onDismissRequest = onDismiss,
        title = { Text(title.orEmpty()) },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(timePickerState.hour, timePickerState.minute) },
            ) {
                Text(stringResource(UiR.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(UiR.string.action_cancel))
            }
        },
    ) {
        TimePicker(
            state = timePickerState,
            modifier = Modifier.padding(horizontal = Spacing.xxl),
        )
    }
}

/** Runs an action row after asking, under the watch's own wording. */
@Composable
private fun ConfirmActionDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.settings_watch_settings_confirm_action, title))
        },
        text = { Text(stringResource(R.string.settings_watch_settings_confirm_action_body)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(title)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(UiR.string.action_cancel))
            }
        },
    )
}

@Composable
private fun Message(
    text: String,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.xxl),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(bottom = Spacing.lg),
                )
            }
            Text(
                text = text,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onRetry != null) {
                FilledTonalButton(
                    onClick = onRetry,
                    modifier = Modifier.padding(top = Spacing.md),
                ) {
                    Text(retryLabel.orEmpty())
                }
            }
        }
    }
}

/** The small in-row progress spinner's stroke. */
private val WatchProgressStroke = 2.dp
/** Vertical padding of one setting row. */
private val WatchSettingRowPadding = 10.dp
