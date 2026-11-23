package tech.mmarca.openwatch.features.watches

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.core.ui.R as UiR
import tech.mmarca.openwatch.features.device.R as DeviceR
import tech.mmarca.openwatch.core.presentation.joinParts
import tech.mmarca.openwatch.core.presentation.localizedList
import tech.mmarca.openwatch.devices.garmin.GarminAlarm
import tech.mmarca.openwatch.devices.garmin.GarminAlarmLabel
import tech.mmarca.openwatch.devices.garmin.GarminAlarmSound
import tech.mmarca.openwatch.devices.garmin.GarminAlarmsFile
import tech.mmarca.openwatch.devices.garmin.GarminSendFileResult
import tech.mmarca.openwatch.devices.garmin.GarminSendStage
import tech.mmarca.openwatch.devices.garmin.GarminUploadRefusal
import tech.mmarca.openwatch.ui.components.OpenWatchButton
import tech.mmarca.openwatch.ui.components.OpenWatchCard
import tech.mmarca.openwatch.ui.components.OpenWatchOutlinedButton
import tech.mmarca.openwatch.ui.components.OptionDropdown
import tech.mmarca.openwatch.ui.components.ScreenColumn
import tech.mmarca.openwatch.ui.components.SwitchRow
import tech.mmarca.openwatch.ui.theme.OpenWatchTheme
import tech.mmarca.openwatch.ui.theme.Spacing

/** The alarm being edited: a new one, or the one at [index]. */
data class AlarmEdit(val index: Int?, val alarm: GarminAlarm)

/**
 * The watch-alarms destination: the only place that knows the ViewModel. It holds which
 * alarm the editor is open on; [WatchAlarmsScreen] draws.
 */
@Composable
fun WatchAlarmsRoute(viewModel: WatchAlarmsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AlarmEdit?>(null) }

    WatchAlarmsScreen(
        state = state,
        editing = editing,
        onEditingChange = { editing = it },
        onToggleAlarm = viewModel::setEnabled,
        onSaveAlarm = viewModel::save,
        onDeleteAlarm = viewModel::delete,
        onSend = viewModel::send,
    )
}

/**
 * The alarms of a watch with no settings tree. Edits stay on the phone until
 * the user taps Send, and a send replaces every alarm on the watch.
 */
@Composable
fun WatchAlarmsScreen(
    state: WatchAlarmsUiState,
    /** The alarm the editor is open on; null keeps it closed. */
    editing: AlarmEdit?,
    onEditingChange: (AlarmEdit?) -> Unit,
    onToggleAlarm: (index: Int, enabled: Boolean) -> Unit,
    onSaveAlarm: (index: Int?, alarm: GarminAlarm) -> Unit,
    onDeleteAlarm: (index: Int) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalPadding = Spacing.lg,
    ) {
        Text(
            text = stringResource(R.string.settings_watch_alarms_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.alarms.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_watch_alarms_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        state.alarms.forEachIndexed { index, alarm ->
            AlarmRow(
                alarm = alarm,
                enabled = !state.isSending,
                onOpen = { onEditingChange(AlarmEdit(index, alarm)) },
                onToggle = { onToggleAlarm(index, it) },
            )
        }

        OpenWatchOutlinedButton(
            onClick = { onEditingChange(AlarmEdit(index = null, alarm = GarminAlarm(hour = 7, minute = 0))) },
            enabled = state.canAdd,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                modifier = Modifier.size(AlarmIconSize),
            )
            Spacer(modifier = Modifier.width(Spacing.sm))
            Text(stringResource(R.string.settings_watch_alarms_add))
        }
        if (state.alarms.size >= GarminAlarmsFile.MaxAlarms) {
            Text(
                text = stringResource(R.string.settings_watch_alarms_limit, GarminAlarmsFile.MaxAlarms),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OpenWatchButton(
            onClick = onSend,
            enabled = state.canSend,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isSending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(AlarmIconSize),
                    strokeWidth = AlarmProgressStroke,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Alarm,
                    contentDescription = null,
                    modifier = Modifier.size(AlarmIconSize),
                )
            }
            Spacer(modifier = Modifier.width(Spacing.sm))
            Text(stringResource(R.string.settings_watch_alarms_send))
        }
        SendStatus(state)
    }

    editing?.let { edit ->
        AlarmEditorDialog(
            edit = edit,
            onDismiss = { onEditingChange(null) },
            onSave = { alarm ->
                onSaveAlarm(edit.index, alarm)
                onEditingChange(null)
            },
            onDelete = {
                edit.index?.let(onDeleteAlarm)
                onEditingChange(null)
            },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun WatchAlarmsScreenPreview() {
    val alarms = listOf(
        GarminAlarm(
            hour = 7,
            minute = 0,
            days = DayOfWeek.entries.take(5).toSet(),
            label = GarminAlarmLabel.WAKE_UP,
        ),
        GarminAlarm(hour = 9, minute = 30, enabled = false),
    )
    OpenWatchTheme {
        WatchAlarmsScreen(
            state = WatchAlarmsUiState(alarms = alarms, unsent = true),
            editing = AlarmEdit(index = 0, alarm = alarms[0]),
            onEditingChange = {},
            onToggleAlarm = { _, _ -> },
            onSaveAlarm = { _, _ -> },
            onDeleteAlarm = {},
            onSend = {},
        )
    }
}

@Composable
private fun AlarmRow(
    alarm: GarminAlarm,
    enabled: Boolean,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    OpenWatchCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onOpen)
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = alarmTimeText(alarm), style = MaterialTheme.typography.titleLarge)
                Text(
                    text = alarmSummary(alarm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(Spacing.md))
            Switch(checked = alarm.enabled, onCheckedChange = onToggle, enabled = enabled)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AlarmEditorDialog(
    edit: AlarmEdit,
    onDismiss: () -> Unit,
    onSave: (GarminAlarm) -> Unit,
    onDelete: () -> Unit,
) {
    var alarm by remember(edit) { mutableStateOf(edit.alarm) }
    var showTimePicker by remember { mutableStateOf(false) }
    val locale = LocalLocale.current.platformLocale

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (edit.index == null) {
                        R.string.settings_watch_alarms_new
                    } else {
                        R.string.settings_watch_alarms_edit
                    },
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                TextButton(onClick = { showTimePicker = true }) {
                    Text(text = alarmTimeText(alarm), style = MaterialTheme.typography.headlineMedium)
                }

                EditorLabel(R.string.settings_watch_alarms_repeat)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    DayOfWeek.entries.forEach { day ->
                        FilterChip(
                            selected = day in alarm.days,
                            onClick = {
                                alarm = alarm.copy(
                                    days = if (day in alarm.days) alarm.days - day else alarm.days + day,
                                )
                            },
                            label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
                        )
                    }
                }

                EditorLabel(R.string.settings_watch_alarms_sound)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    GarminAlarmSound.entries.forEach { sound ->
                        FilterChip(
                            selected = sound == alarm.sound,
                            onClick = { alarm = alarm.copy(sound = sound) },
                            label = { Text(stringResource(alarmSoundText(sound))) },
                        )
                    }
                }

                // "Not specified" is the watch's NONE.
                OptionDropdown(
                    label = stringResource(R.string.settings_watch_alarms_label),
                    options = GarminAlarmLabel.entries - GarminAlarmLabel.NONE,
                    selected = alarm.label.takeIf { it != GarminAlarmLabel.NONE },
                    optionText = { stringResource(alarmLabelText(it)) },
                    enabled = true,
                    onSelect = { alarm = alarm.copy(label = it ?: GarminAlarmLabel.NONE) },
                )

                // The dialog already holds the inset.
                SwitchRow(
                    title = stringResource(R.string.settings_watch_alarms_backlight),
                    checked = alarm.backlight,
                    onCheckedChange = { alarm = alarm.copy(backlight = it) },
                    contentPadding = PaddingValues(0.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(alarm) }) { Text(stringResource(UiR.string.action_save)) }
        },
        dismissButton = {
            Row {
                if (edit.index != null) {
                    TextButton(onClick = onDelete) {
                        Text(
                            text = stringResource(UiR.string.action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.action_cancel)) }
            }
        },
    )

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = alarm.hour,
            initialMinute = alarm.minute,
            is24Hour = DateFormat.is24HourFormat(LocalContext.current),
        )
        TimePickerDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text(stringResource(R.string.settings_watch_alarms_time)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        alarm = alarm.copy(hour = timePickerState.hour, minute = timePickerState.minute)
                        showTimePicker = false
                    },
                ) {
                    Text(stringResource(UiR.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(stringResource(UiR.string.action_cancel))
                }
            },
        ) {
            TimePicker(state = timePickerState, modifier = Modifier.padding(horizontal = Spacing.xxl))
        }
    }
}

@Composable
private fun EditorLabel(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Progress while sending, then how it ended, inline beside what was sent. */
@Composable
private fun SendStatus(state: WatchAlarmsUiState) {
    val result = state.result
    val (text, isError) = when {
        state.isSending -> alarmsStageText(state.sendingStage) to false
        result != null -> alarmsResultText(result) to (result != GarminSendFileResult.Sent)
        state.radioBusy -> DeviceR.string.settings_watch_point_failed_busy to false
        state.unsent -> R.string.settings_watch_alarms_unsent to false
        else -> return
    }
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun alarmTimeText(alarm: GarminAlarm): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(LocalLocale.current.platformLocale)
        .format(LocalTime.of(alarm.hour, alarm.minute))

/** The days, then the label when there is one: "Mon, Tue · Wake up". */
@Composable
private fun alarmSummary(alarm: GarminAlarm): String {
    val locale = LocalLocale.current.platformLocale
    val days = when (alarm.days) {
        emptySet<DayOfWeek>() -> stringResource(R.string.settings_watch_alarms_once)
        DayOfWeek.entries.toSet() -> stringResource(R.string.settings_watch_alarms_every_day)
        else -> localizedList(DayOfWeek.entries.filter { it in alarm.days }.map { it.getDisplayName(TextStyle.SHORT, locale) }, locale)
    }
    if (alarm.label == GarminAlarmLabel.NONE) return days
    return joinParts(listOf(days, stringResource(alarmLabelText(alarm.label))))
}

@StringRes
private fun alarmsStageText(stage: GarminSendStage?): Int = when (stage) {
    GarminSendStage.SENDING -> R.string.settings_watch_alarms_stage_sending
    GarminSendStage.CONNECTING, null -> R.string.settings_watch_point_stage_connecting
}

/** The point screen's wording, except where it speaks of a point. */
@StringRes
internal fun alarmsResultText(result: GarminSendFileResult): Int = when (result) {
    GarminSendFileResult.Sent -> R.string.settings_watch_alarms_sent
    is GarminSendFileResult.Refused -> when (result.reason) {
        GarminUploadRefusal.UNSUPPORTED -> R.string.settings_watch_alarms_failed_unsupported
        GarminUploadRefusal.NOT_READY,
        GarminUploadRefusal.SYNC_PAUSED -> R.string.settings_watch_point_failed_not_ready
        else -> R.string.settings_watch_point_failed_rejected
    }
    else -> sendResultText(result)
}

@StringRes
private fun alarmSoundText(sound: GarminAlarmSound): Int = when (sound) {
    GarminAlarmSound.OFF -> R.string.settings_watch_alarms_sound_off
    GarminAlarmSound.SOUND -> R.string.settings_watch_alarms_sound_tone
    GarminAlarmSound.VIBRATION -> R.string.settings_watch_alarms_sound_vibration
    GarminAlarmSound.SOUND_AND_VIBRATION -> R.string.settings_watch_alarms_sound_both
}

@StringRes
private fun alarmLabelText(label: GarminAlarmLabel): Int = when (label) {
    GarminAlarmLabel.NONE -> UiR.string.option_not_specified
    GarminAlarmLabel.WAKE_UP -> R.string.settings_watch_alarms_label_wake_up
    GarminAlarmLabel.WORKOUT -> R.string.settings_watch_alarms_label_workout
    GarminAlarmLabel.REMINDER -> R.string.settings_watch_alarms_label_reminder
    GarminAlarmLabel.APPOINTMENT -> R.string.settings_watch_alarms_label_appointment
    GarminAlarmLabel.TRAINING -> R.string.settings_watch_alarms_label_training
    GarminAlarmLabel.CLASS -> R.string.settings_watch_alarms_label_class
    GarminAlarmLabel.MEDITATE -> R.string.settings_watch_alarms_label_meditate
    GarminAlarmLabel.BEDTIME -> R.string.settings_watch_alarms_label_bedtime
}

/** The buttons' leading icon and the spinner share a size, as on the point screen. */
private val AlarmIconSize = 18.dp

private val AlarmProgressStroke = 2.dp
