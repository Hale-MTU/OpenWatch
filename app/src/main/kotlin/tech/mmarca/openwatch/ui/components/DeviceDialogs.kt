package tech.mmarca.openwatch.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.ui.theme.Spacing

/** Renames a paired device: [title] names what is renamed, [onSave] gets the text as typed. */
@Composable
fun RenameDialog(
    title: String,
    initialName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.settings_watch_name_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Confirms removing a paired device. [option], when given, is a checkbox under the [body], off
 * by default, whose answer [onConfirm] receives: what else may go with the device.
 */
@Composable
fun ConfirmRemoveDialog(
    title: String,
    body: String,
    onConfirm: (optionChecked: Boolean) -> Unit,
    onDismiss: () -> Unit,
    option: String? = null,
) {
    var optionChecked by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(body)
                if (option != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Spacing.md)
                            .toggleable(value = optionChecked, role = Role.Checkbox, onValueChange = { optionChecked = it }),
                    ) {
                        Checkbox(checked = optionChecked, onCheckedChange = null)
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Text(option)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(option != null && optionChecked) }) {
                Text(text = stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
