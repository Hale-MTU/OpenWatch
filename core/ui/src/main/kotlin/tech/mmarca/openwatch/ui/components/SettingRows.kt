package tech.mmarca.openwatch.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import tech.mmarca.openwatch.ui.theme.Spacing

/** The padding a [SwitchRow] keeps inside a card. */
val SwitchRowPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm)

/**
 * A setting that is a switch: its [title], an optional [body] under it, and the switch, as one
 * control. The whole row toggles, and TalkBack reads the title with the state; a bare `Switch`
 * beside a `Text` is announced as "switch, off" with no name.
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues = SwitchRowPadding,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(contentPadding),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (body != null) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(Spacing.md))
        // The row toggles; the switch only shows the state.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A [SwitchRow] on its own card. */
@Composable
fun SwitchCard(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    enabled: Boolean = true,
) {
    OpenWatchCard(modifier = modifier) {
        SwitchRow(title = title, checked = checked, onCheckedChange = onCheckedChange, body = body, enabled = enabled)
    }
}

/** The title of a group of cards, with a rule to the edge. A heading for TalkBack's navigation by heading. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.width(Spacing.sm))
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}
