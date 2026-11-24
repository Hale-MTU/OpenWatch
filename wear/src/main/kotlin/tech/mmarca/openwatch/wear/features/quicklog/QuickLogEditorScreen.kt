package tech.mmarca.openwatch.wear.features.quicklog

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.AppScaffold
import tech.mmarca.openwatch.wear.Choices
import tech.mmarca.openwatch.wear.QuickLogItem
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.ui.components.ChoiceEditor
import tech.mmarca.openwatch.wear.ui.components.ChoiceEditorText
import tech.mmarca.openwatch.wear.ui.components.ChoiceLook
import tech.mmarca.openwatch.wear.ui.preview.WearPreviews
import tech.mmarca.openwatch.wear.ui.theme.OpenWatchWearTheme

/** What the quick log's editor does with its chosen entries: a change of them, and back to every entry. */
internal class QuickLogChoice(
    val edit: ((List<QuickLogItem>) -> List<QuickLogItem>) -> Unit,
    val reset: () -> Unit,
)

/** An item's name on the quick log and in its editor. */
@get:StringRes
internal val QuickLogItem.label: Int
    get() = when (this) {
        QuickLogItem.WATER -> R.string.metric_hydration
        QuickLogItem.WEIGHT -> R.string.metric_weight
        QuickLogItem.BLOOD_PRESSURE -> R.string.quicklog_blood_pressure
        QuickLogItem.BREATHE -> R.string.breathe_title
        // A value of one number is named by its stepper.
        else -> LogValueSpecs.getValue(type).label
    }

/**
 * Chooses which entries the quick log shows and in which order: the chosen
 * ones first, each with arrows that move it a place up or down, then the
 * others to switch on, and a reset to every entry. The list is the shared
 * [ChoiceEditor], the one the dashboard's tiles use; each change goes to
 * [onEdit] as a change of the stored choice, so two quick taps both count.
 */
@Composable
fun QuickLogEditorScreen(
    items: List<QuickLogItem>,
    onEdit: ((List<QuickLogItem>) -> List<QuickLogItem>) -> Unit,
    onReset: () -> Unit,
) {
    ChoiceEditor(
        chosen = items,
        others = QuickLogItem.entries.filter { it !in items },
        look = { ChoiceLook(it.label, it.type.icon, it.type.color) },
        key = { it.name },
        text = ChoiceEditorText(
            title = stringResource(R.string.quicklog_editor_title),
            chosenHeader = stringResource(R.string.quicklog_editor_active_header),
            othersHeader = stringResource(R.string.quicklog_editor_available_header),
            reset = stringResource(R.string.tiles_reset_default),
            hint = stringResource(R.string.quicklog_editor_move_hint),
        ),
        onSwitch = { item, on -> onEdit { Choices.switched(it, item, on) } },
        // Every entry is on offer, so a move trades places with the next chosen one.
        onMove = { item, up -> onEdit { Choices.moved(it, item, up) { true } } },
        onReset = onReset,
    )
}

@WearPreviews
@Composable
private fun QuickLogEditorScreenPreview() {
    OpenWatchWearTheme {
        AppScaffold {
            QuickLogEditorScreen(items = QuickLogItem.Defaults.take(5), onEdit = {}, onReset = {})
        }
    }
}
