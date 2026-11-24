package tech.mmarca.openwatch.wear.features.quicklog

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SuccessConfirmationDialog
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import java.util.Date
import tech.mmarca.openwatch.wear.DisplayPreferences
import tech.mmarca.openwatch.wear.MetricTile
import tech.mmarca.openwatch.wear.QuickLogItem
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.UnitSystem
import tech.mmarca.openwatch.wear.WearUiState
import tech.mmarca.openwatch.wear.ui.components.formatMetricValue
import tech.mmarca.openwatch.wear.ui.components.metricUnitLabel
import tech.mmarca.openwatch.wear.ui.preview.SampleData
import tech.mmarca.openwatch.wear.ui.preview.WearPreviews
import tech.mmarca.openwatch.wear.ui.theme.OpenWatchWearTheme
import tech.mmarca.openwatch.wearlink.WearLinkEntry

/** Millilitres one tap on "water" adds: a glass, as on the quick log tile. */
const val WaterServingMl = DisplayPreferences.WATER_SERVING_ML

private const val RecentEntries = 6

/**
 * Fast entries, the watch side of the phone's manual entry: one tap for a
 * glass of water, a stepper for weight, blood pressure, body fat, blood
 * oxygen, breathing rate, body temperature, blood glucose and height, a
 * guided breath. The wearer chooses which show and in which order
 * ([WearUiState.quickLogItems]); the last button edits that ([onEditItems]).
 * The latest entries made by hand are listed underneath so a mistake is easy
 * to spot; a spot measurement's result is not one.
 */
@Composable
fun QuickLogScreen(
    state: WearUiState,
    onAddWater: () -> Unit,
    onLogWeight: () -> Unit,
    onLogBloodPressure: () -> Unit,
    onLogValue: (WearLinkEntry.Type) -> Unit,
    onBreathe: () -> Unit,
    onEditItems: () -> Unit = {},
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val unitSystem = state.preferences.unitSystem
    var showSaved by remember { mutableStateOf(false) }
    val notLogged = stringResource(R.string.quicklog_not_logged)

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                ListHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    Text(stringResource(R.string.quicklog_title))
                }
            }
            if (state.quickLogItems.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.quicklog_none_chosen),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(state.quickLogItems, key = { it.name }) { item ->
                val (secondary, onClick) = when (item) {
                    QuickLogItem.WATER -> waterToday(state) to {
                        onAddWater()
                        showSaved = true
                    }
                    QuickLogItem.WEIGHT -> lastOf(state, item, notLogged) to onLogWeight
                    QuickLogItem.BLOOD_PRESSURE -> lastOf(state, item, notLogged) to onLogBloodPressure
                    QuickLogItem.BREATHE -> stringResource(R.string.breathe_description) to onBreathe
                    else -> lastOf(state, item, notLogged) to { onLogValue(item.type) }
                }
                QuickLogButton(
                    label = if (item == QuickLogItem.WATER) waterLabel(unitSystem) else stringResource(item.label),
                    secondary = secondary,
                    icon = item.type.icon,
                    color = item.type.color,
                    onClick = onClick,
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                )
            }
            val recent = state.entries.filterNot { it.measured }.takeLast(RecentEntries).asReversed()
            if (recent.isNotEmpty()) {
                item { ListSubHeader { Text(stringResource(R.string.quicklog_recent)) } }
                items(recent, key = { it.epochMillis }) { entry -> RecentEntryRow(entry, unitSystem) }
            }
            item {
                Button(
                    onClick = onEditItems,
                    label = { Text(stringResource(R.string.quicklog_edit)) },
                    icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    colors = ButtonDefaults.outlinedButtonColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                )
            }
        }
    }

    SuccessConfirmationDialog(
        visible = showSaved,
        onDismissRequest = { showSaved = false },
        curvedText = null,
    )
}

/** The water button's label: the glass it adds. */
@Composable
private fun waterLabel(unitSystem: UnitSystem): String = stringResource(
    R.string.quicklog_add_water,
    formatMetricValue(MetricTile.HYDRATION, WaterServingMl, unitSystem),
    stringResource(metricUnitLabel(MetricTile.HYDRATION, unitSystem)),
)

/** The day's water against its goal. */
@Composable
private fun waterToday(state: WearUiState): String {
    val unitSystem = state.preferences.unitSystem
    return stringResource(
        R.string.quicklog_water_today,
        formatMetricValue(MetricTile.HYDRATION, state.metrics[MetricTile.HYDRATION]?.current ?: 0.0, unitSystem),
        formatMetricValue(MetricTile.HYDRATION, state.preferences.waterGoalMl.toDouble(), unitSystem),
        stringResource(metricUnitLabel(MetricTile.HYDRATION, unitSystem)),
    )
}

/** The last value of [item]'s type logged, or [notLogged]. */
@Composable
private fun lastOf(state: WearUiState, item: QuickLogItem, notLogged: String): String =
    state.entries.lastOrNull { it.type == item.type }?.let { formatEntry(it, state.preferences.unitSystem) } ?: notLogged

@Composable
private fun QuickLogButton(
    label: String,
    secondary: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier,
    transformation: SurfaceTransformation,
) {
    Button(
        onClick = onClick,
        label = { Text(label) },
        secondaryLabel = { Text(secondary) },
        icon = { Icon(icon, contentDescription = null, tint = color) },
        colors = ButtonDefaults.filledTonalButtonColors(),
        modifier = modifier.fillMaxWidth(),
        transformation = transformation,
    )
}

@Composable
private fun RecentEntryRow(entry: WearLinkEntry, unitSystem: UnitSystem) {
    val context = LocalContext.current
    val time = remember(entry.epochMillis) { DateFormat.getTimeFormat(context).format(Date(entry.epochMillis)) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(entry.type.icon, contentDescription = null, tint = entry.type.color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = formatEntry(entry, unitSystem),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = time,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@WearPreviews
@Composable
private fun QuickLogScreenPreview() {
    OpenWatchWearTheme {
        AppScaffold {
            QuickLogScreen(SampleData.uiState, {}, {}, {}, {}, {}, {})
        }
    }
}
