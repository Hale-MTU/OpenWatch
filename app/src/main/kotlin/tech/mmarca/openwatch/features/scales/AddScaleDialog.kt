package tech.mmarca.openwatch.features.scales

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.devices.core.pairing.CompanionDevice
import tech.mmarca.openwatch.ui.components.OpenWatchTextButton
import tech.mmarca.openwatch.ui.components.OpenWatchTonalButton
import tech.mmarca.openwatch.ui.theme.OpenWatchTheme
import tech.mmarca.openwatch.ui.theme.Spacing

/**
 * Adding a scale, in two steps: Android's companion dialog finds it, which
 * also lets Android wake the app for it, then the key that opens its
 * broadcasts. The dialog cannot be dismissed while Android's is up.
 */
@Composable
internal fun AddScaleDialog(
    state: ScalesUiState,
    onFind: () -> Unit,
    onNameChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    /** Where the scale's key is found: the supported-scales page. */
    onOpenKeyHelp: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!state.isFinding) onClose() },
        title = { Text(text = stringResource(R.string.settings_scales_add)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                StepRow(
                    label = stringResource(R.string.settings_scales_find_title),
                    active = state.isFinding,
                    done = state.addStep == AddScaleStep.KEY,
                )
                if (state.addStep == AddScaleStep.FIND) FindStep(state, onFind = onFind)
                StepRow(
                    label = stringResource(R.string.settings_scales_key_step),
                    active = false,
                    done = false,
                )
                if (state.addStep == AddScaleStep.KEY) {
                    KeyStep(
                        state = state,
                        onNameChange = onNameChange,
                        onKeyChange = onKeyChange,
                        onOpenHelp = onOpenKeyHelp,
                    )
                }
            }
        },
        confirmButton = {
            if (state.addStep == AddScaleStep.KEY) {
                TextButton(onClick = onSave) { Text(text = stringResource(R.string.action_save)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onClose, enabled = !state.isFinding) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun FindStep(state: ScalesUiState, onFind: () -> Unit) {
    Column(
        modifier = Modifier.padding(start = StepIndent),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.settings_scales_find_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.findFailed) {
            Text(
                text = stringResource(R.string.settings_scales_wake_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        OpenWatchTonalButton(onClick = onFind, enabled = !state.isFinding) {
            Text(
                text = stringResource(
                    if (state.isFinding) R.string.settings_scales_find_searching else R.string.settings_scales_find_action,
                ),
            )
        }
    }
}

@Composable
private fun KeyStep(
    state: ScalesUiState,
    onNameChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onOpenHelp: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(start = StepIndent),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        OutlinedTextField(
            value = state.nameInput,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.settings_watch_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.settings_scales_key_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OpenWatchTextButton(onClick = onOpenHelp) {
            Text(stringResource(R.string.settings_scales_key_help_action))
        }
        ScaleKeyField(
            value = state.keyInput,
            onValueChange = onKeyChange,
            isError = state.keyInputInvalid,
        )
    }
}

/** The bind-key field: monospace, no suggestions, with the format complaint under it. */
@Composable
internal fun ScaleKeyField(value: String, onValueChange: (String) -> Unit, isError: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.settings_scales_key_label)) },
        isError = isError,
        supportingText = if (isError) {
            { Text(stringResource(R.string.settings_scales_key_invalid)) }
        } else {
            null
        },
        singleLine = true,
        // A key is not a word: no suggestions, no learned entries.
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
        ),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StepRow(label: String, active: Boolean, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            active -> CircularProgressIndicator(modifier = Modifier.size(Spacing.lg), strokeWidth = ProgressStroke)
            done -> Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(StepIconSize),
                tint = MaterialTheme.colorScheme.primary,
            )

            else -> Icon(
                imageVector = Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                modifier = Modifier.size(StepIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (active || done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AddScaleDialogKeyStepPreview() {
    OpenWatchTheme {
        AddScaleDialog(
            state = ScalesUiState(
                showAddFlow = true,
                addStep = AddScaleStep.KEY,
                found = CompanionDevice(address = "5C:E5:0C:4A:91:2F", name = "MIBFS"),
                nameInput = "Bathroom",
                keyInput = "9f3a6c21e04b7d58a1c2e9f0b36d4a70",
            ),
            onFind = {},
            onNameChange = {},
            onKeyChange = {},
            onOpenKeyHelp = {},
            onSave = {},
            onClose = {},
        )
    }
}

private val StepIconSize = 18.dp
private val ProgressStroke = 2.dp
private val StepIndent = Spacing.xxl
