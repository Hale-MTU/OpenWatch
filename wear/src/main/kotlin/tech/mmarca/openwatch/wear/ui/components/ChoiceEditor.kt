package tech.mmarca.openwatch.wear.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import tech.mmarca.openwatch.wear.R

/** How one item of a [ChoiceEditor] looks: its name, its icon and the colour the icon takes. */
class ChoiceLook(@param:StringRes val label: Int, val icon: ImageVector, val color: Color)

/** The words of one [ChoiceEditor]; a null hint or footer is left out. */
class ChoiceEditorText(
    val title: String,
    /** Over the items chosen. */
    val chosenHeader: String,
    /** Over the items that may be added. */
    val othersHeader: String,
    val reset: String,
    /** Under the items chosen: how to move or remove one. */
    val hint: String? = null,
    /** At the end: why an item may be missing from the list. */
    val footer: String? = null,
)

/**
 * An ordered choice from a set of items, edited on a round screen: the
 * [chosen] items first, in their order, each switched on and with arrows
 * that move it one place up or down; then the [others] that may be added,
 * switched off. A tap on an item switches it ([onSwitch]), an arrow moves it
 * ([onMove]), and the reset brings the defaults back ([onReset]).
 *
 * Stateless and generic: the caller keeps the choice and applies each change
 * (`Choices` in `:wear:recording-core`), so the dashboard's tiles and any
 * other list the wearer orders use the same screen. Arrows rather than a
 * drag, which a list that scrolls with the crown and swipes back would fight.
 */
@Composable
fun <T : Any> ChoiceEditor(
    chosen: List<T>,
    others: List<T>,
    look: (T) -> ChoiceLook,
    /** A key that names [T] apart from every other item, stable across changes. */
    key: (T) -> String,
    text: ChoiceEditorText,
    onSwitch: (item: T, on: Boolean) -> Unit,
    onMove: (item: T, up: Boolean) -> Unit,
    onReset: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item(key = "title") {
                ListHeader(
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                ) { Text(text.title) }
            }
            if (chosen.isNotEmpty()) {
                item(key = "chosen") { ListSubHeader { Text(text.chosenHeader) } }
                chosen.forEachIndexed { index, choice ->
                    item(key = "on:${key(choice)}") {
                        ChoiceSwitch(
                            look = look(choice),
                            checked = true,
                            onCheckedChange = { onSwitch(choice, it) },
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                            transformation = SurfaceTransformation(spec),
                        )
                    }
                    item(key = "move:${key(choice)}") {
                        MoveArrows(
                            label = stringResource(look(choice).label),
                            canMoveUp = index > 0,
                            canMoveDown = index < chosen.lastIndex,
                            onMove = { up -> onMove(choice, up) },
                        )
                    }
                }
                text.hint?.let { hint -> item(key = "hint") { Hint(hint) } }
            }
            if (others.isNotEmpty()) {
                item(key = "others") { ListSubHeader { Text(text.othersHeader) } }
                others.forEach { choice ->
                    item(key = "off:${key(choice)}") {
                        ChoiceSwitch(
                            look = look(choice),
                            checked = false,
                            onCheckedChange = { onSwitch(choice, it) },
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                            transformation = SurfaceTransformation(spec),
                        )
                    }
                }
            }
            item(key = "reset") {
                OutlinedButton(
                    onClick = onReset,
                    label = { Text(text.reset) },
                    icon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                )
            }
            text.footer?.let { footer -> item(key = "footer") { Hint(footer) } }
        }
    }
}

@Composable
private fun ChoiceSwitch(
    look: ChoiceLook,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier,
    transformation: SurfaceTransformation,
) {
    SwitchButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        label = { Text(stringResource(look.label)) },
        icon = { Icon(look.icon, contentDescription = null, tint = look.color) },
        modifier = modifier,
        transformation = transformation,
    )
}

/** The two arrows under a chosen item; the one that would move it past an end is off. */
@Composable
private fun MoveArrows(label: String, canMoveUp: Boolean, canMoveDown: Boolean, onMove: (up: Boolean) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        modifier = Modifier.fillMaxWidth(),
    ) {
        FilledTonalIconButton(onClick = { onMove(true) }, enabled = canMoveUp, modifier = Modifier.size(ArrowSize)) {
            Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = stringResource(R.string.choice_move_up, label))
        }
        FilledTonalIconButton(onClick = { onMove(false) }, enabled = canMoveDown, modifier = Modifier.size(ArrowSize)) {
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(R.string.choice_move_down, label))
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** Smaller than an icon button's default, so the arrows read as part of the item above them. */
private val ArrowSize = 40.dp
