package tech.mmarca.openwatch.wear.features.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.AppScaffold
import tech.mmarca.openwatch.wear.Choices
import tech.mmarca.openwatch.wear.MetricTile
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.ui.components.ChoiceEditor
import tech.mmarca.openwatch.wear.ui.components.ChoiceEditorText
import tech.mmarca.openwatch.wear.ui.components.ChoiceLook
import tech.mmarca.openwatch.wear.ui.preview.SampleData
import tech.mmarca.openwatch.wear.ui.preview.WearPreviews
import tech.mmarca.openwatch.wear.ui.theme.OpenWatchWearTheme

/**
 * Adds, removes and orders the dashboard's tiles. Only the [available]
 * metrics are listed, those this watch offers now: a night's vital once a
 * night gave one, never one without its sensor. A chosen tile that is not on
 * offer stays chosen, out of sight, and keeps its place. Each change goes to
 * [onEdit] as a change of the stored choice, so two quick taps both count.
 */
@Composable
fun TileEditorScreen(
    available: Set<MetricTile>,
    tiles: List<MetricTile>,
    onEdit: ((List<MetricTile>) -> List<MetricTile>) -> Unit,
    onReset: () -> Unit,
) {
    val shown = tiles.filter { it in available }
    ChoiceEditor(
        chosen = shown,
        others = MetricTile.entries.filter { it in available && it !in tiles },
        look = { ChoiceLook(it.label, it.icon, it.accentColor) },
        key = { it.name },
        text = ChoiceEditorText(
            title = stringResource(R.string.tiles_title),
            chosenHeader = stringResource(R.string.tiles_active_header),
            othersHeader = stringResource(R.string.tiles_available_header),
            reset = stringResource(R.string.tiles_reset_default),
            hint = stringResource(R.string.tiles_move_hint),
            footer = stringResource(R.string.tiles_sensor_hint),
        ),
        onSwitch = { metric, on -> onEdit { Choices.switched(it, metric, on) } },
        onMove = { metric, up -> onEdit { Choices.moved(it, metric, up) { tile -> tile in available } } },
        onReset = onReset,
    )
}

@WearPreviews
@Composable
private fun TileEditorScreenPreview() {
    OpenWatchWearTheme {
        AppScaffold {
            TileEditorScreen(
                available = SampleData.uiState.availableMetrics,
                tiles = MetricTile.DefaultTiles,
                onEdit = {},
                onReset = {},
            )
        }
    }
}
