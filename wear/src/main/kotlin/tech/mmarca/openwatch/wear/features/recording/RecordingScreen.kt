package tech.mmarca.openwatch.wear.features.recording

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.PagerState
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import tech.mmarca.healthcalculations.insights.HeartRateZones
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.UnitSystem
import tech.mmarca.openwatch.wear.MetricTile
import tech.mmarca.openwatch.wear.features.metric.HeartZoneDisplay
import tech.mmarca.openwatch.wear.ui.components.MetricValueRow
import tech.mmarca.openwatch.wear.ui.components.formatCount
import tech.mmarca.openwatch.wear.ui.components.formatMetricValue
import tech.mmarca.openwatch.wear.ui.components.metricUnitLabel
import tech.mmarca.openwatch.wear.ui.preview.SampleData
import tech.mmarca.openwatch.wear.ui.preview.WearPreviews
import tech.mmarca.openwatch.wear.ui.theme.DistanceColor
import tech.mmarca.openwatch.wear.ui.theme.ElevationColor
import tech.mmarca.openwatch.wear.ui.theme.Emphasis
import tech.mmarca.openwatch.wear.ui.theme.HeartColor
import tech.mmarca.openwatch.wear.ui.theme.HeartZoneColors
import tech.mmarca.openwatch.wear.ui.theme.OpenWatchWearTheme
import tech.mmarca.openwatch.wear.ui.theme.StepsColor
import tech.mmarca.openwatch.wear.ui.theme.WorkoutColor

internal enum class WorkoutPage { CONTROLS, OVERVIEW, HEART, PACE }

/** The pages a workout that reports [fields] has, left to right: the controls, the overview, then the pages its values fill. */
internal fun workoutPagesOf(fields: Set<WorkoutField>): List<WorkoutPage> = buildList {
    add(WorkoutPage.CONTROLS)
    add(WorkoutPage.OVERVIEW)
    if (WorkoutField.HEART_RATE in fields) add(WorkoutPage.HEART)
    if (fields.any { it == WorkoutField.PACE || it == WorkoutField.SPEED || it == WorkoutField.ELEVATION }) {
        add(WorkoutPage.PACE)
    }
}

/**
 * What the running workout's screen keeps while it is not drawn: the page
 * the wearer is on, and an open "End workout?". `RunningWorkout` takes the
 * screen out of composition in ambient mode, so its caller remembers this
 * above it ([rememberRecordingScreenState]) and both are there again when the
 * watch wakes.
 */
@Stable
class RecordingScreenState internal constructor(
    internal val pages: List<WorkoutPage>,
    internal val pagerState: PagerState,
    confirmStop: MutableState<Boolean>,
) {
    internal var confirmStop by confirmStop
}

/** The screen state of a workout that reports [fields], opened on the overview. */
@Composable
fun rememberRecordingScreenState(fields: Set<WorkoutField>): RecordingScreenState {
    val pages = remember(fields) { workoutPagesOf(fields) }
    val pagerState = rememberPagerState(initialPage = pages.indexOf(WorkoutPage.OVERVIEW)) { pages.size }
    val confirmStop = rememberSaveable { mutableStateOf(false) }
    return remember(pages, pagerState, confirmStop) { RecordingScreenState(pages, pagerState, confirmStop) }
}

/**
 * A running workout, laid out like the watch makers' own: the overview in
 * the middle, controls one swipe to the left, heart rate zones and pace to
 * the right. Only pages and values the workout reports in [fields] are shown.
 * The zones are those of [zoneBasis], the maximum and resting rate the phone
 * sent; without one (the settings are still being read) no zone is drawn. The
 * screen is not held on: in ambient mode `RunningWorkout` draws its
 * own dim layout instead of this one, and [screenState] is what this one
 * finds again afterwards.
 */
@Composable
fun RecordingScreen(
    state: RecordingUiState,
    fields: Set<WorkoutField>,
    unitSystem: UnitSystem,
    zoneBasis: HeartRateZones.Basis?,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
    screenState: RecordingScreenState = rememberRecordingScreenState(fields),
) {
    val pages = screenState.pages
    val pagerState = screenState.pagerState

    HorizontalPagerScaffold(pagerState = pagerState) {
        HorizontalPager(state = pagerState) { page ->
            when (pages[page]) {
                WorkoutPage.CONTROLS -> ControlsPage(state, onTogglePause, onStop = { screenState.confirmStop = true })
                WorkoutPage.OVERVIEW -> OverviewPage(state, fields, unitSystem, zoneBasis)
                // The arc runs along the screen's edge: this page is the screen, and has nothing to scroll.
                WorkoutPage.HEART -> ScreenScaffold { HeartPage(state, zoneBasis) }
                WorkoutPage.PACE -> PacePage(state, fields, unitSystem)
            }
        }
    }

    AlertDialog(
        visible = screenState.confirmStop,
        onDismissRequest = { screenState.confirmStop = false },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    screenState.confirmStop = false
                    onStop()
                },
            )
        },
        title = { Text(stringResource(R.string.recording_stop_confirm)) },
    )
}

@Composable
private fun OverviewPage(
    state: RecordingUiState,
    fields: Set<WorkoutField>,
    unitSystem: UnitSystem,
    zoneBasis: HeartRateZones.Basis?,
) {
    val zone = zoneBasis?.let { basis -> state.heartRateBpm?.let { HeartZoneDisplay.zoneOf(it, basis) } }
    PageColumn {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(state.activityType.icon, null, tint = WorkoutColor, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(if (state.paused) R.string.recording_paused else state.title ?: state.activityType.label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = DateUtils.formatElapsedTime(state.elapsedSeconds),
            style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (state.paused) Emphasis.disabled else 1f),
        )
        if (state.waitingForFix) {
            Text(
                text = stringResource(R.string.workout_waiting_for_gps),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (WorkoutField.HEART_RATE in fields) {
            FieldRow(
                icon = Icons.Outlined.Favorite,
                color = zone?.let { HeartZoneColors[it] } ?: HeartColor,
                value = state.heartRateBpm?.toString(),
                unit = stringResource(R.string.unit_bpm),
            )
        }
        if (WorkoutField.DISTANCE in fields) {
            FieldRow(
                icon = Icons.Outlined.Straighten,
                color = DistanceColor,
                value = state.distanceMeters?.let { formatWorkoutDistance(it, unitSystem) },
                unit = stringResource(metricUnitLabel(MetricTile.DISTANCE, unitSystem)),
            )
        }
        if (WorkoutField.STEPS in fields) {
            FieldRow(
                icon = Icons.AutoMirrored.Outlined.DirectionsWalk,
                color = StepsColor,
                value = state.steps?.let(::formatCount),
                unit = stringResource(R.string.unit_steps),
            )
        }
    }
}

@Composable
private fun HeartPage(state: RecordingUiState, zoneBasis: HeartRateZones.Basis?) {
    // Without a basis there is no reading to place: the arc stays dim and no zone is named.
    val bpm = state.heartRateBpm?.takeIf { zoneBasis != null }
    val zone = zoneBasis?.let { basis -> bpm?.let { HeartZoneDisplay.zoneOf(it, basis) } }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        HeartZoneArc(
            zone = zone,
            progress = zoneBasis?.let { basis -> bpm?.let { HeartZoneDisplay.arcProgress(it, basis) } } ?: 0f,
            modifier = Modifier.fillMaxSize(),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.Favorite,
                contentDescription = null,
                tint = zone?.let { HeartZoneColors[it] } ?: HeartColor,
                modifier = Modifier.size(20.dp),
            )
            MetricValueRow(
                value = state.heartRateBpm?.toString(),
                unit = stringResource(R.string.unit_bpm),
                valueStyle = MaterialTheme.typography.displayMedium,
            )
            // Without a reading there is no zone to name, not even "below zone 1".
            if (bpm != null) {
                Text(
                    text = zone?.let {
                        stringResource(R.string.zone_label, it + 1, stringResource(HeartZoneDisplay.labels[it]))
                    } ?: stringResource(R.string.zone_below),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PacePage(state: RecordingUiState, fields: Set<WorkoutField>, unitSystem: UnitSystem) {
    PageColumn {
        val speed = state.speedMetersPerSecond
        when {
            WorkoutField.PACE in fields -> BigValue(
                label = stringResource(R.string.recording_pace),
                value = speed?.let { formatPace(it, unitSystem) },
                unit = stringResource(paceUnit(unitSystem)),
            )
            WorkoutField.SPEED in fields -> BigValue(
                label = stringResource(R.string.recording_speed),
                value = speed?.let { formatSpeed(it, unitSystem) },
                unit = stringResource(speedUnit(unitSystem)),
            )
        }
        if (WorkoutField.ELEVATION in fields) {
            FieldRow(
                icon = Icons.Outlined.Terrain,
                color = ElevationColor,
                value = state.elevationGainMeters?.let {
                    formatMetricValue(MetricTile.ELEVATION, it.toDouble(), unitSystem)
                },
                unit = stringResource(metricUnitLabel(MetricTile.ELEVATION, unitSystem)),
            )
        }
    }
}

/** What the pause button does now, its caption and its name for a screen reader: resume a paused workout, pause a running one. */
@StringRes
internal fun pauseActionLabel(paused: Boolean): Int = if (paused) R.string.workout_resume else R.string.workout_pause

/**
 * Pause or resume, and stop. Each button carries its action as its content
 * description, so a screen reader names it; the caption under it says the
 * same and is left out of the semantics, or it would be read twice.
 */
@Composable
private fun ControlsPage(state: RecordingUiState, onTogglePause: () -> Unit, onStop: () -> Unit) {
    val pauseLabel = stringResource(pauseActionLabel(state.paused))
    val stopLabel = stringResource(R.string.workout_stop)
    PageColumn {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledTonalIconButton(onClick = onTogglePause, modifier = Modifier.size(56.dp)) {
                    Icon(if (state.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, contentDescription = pauseLabel)
                }
                Text(
                    text = pauseLabel,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledIconButton(
                    onClick = onStop,
                    modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Icon(Icons.Outlined.Stop, contentDescription = stopLabel)
                }
                Text(text = stopLabel, style = MaterialTheme.typography.labelSmall, modifier = Modifier.clearAndSetSemantics { })
            }
        }
    }
}

/**
 * One page of rows, centred while they fit the screen. Rows that do not fit
 * (a small round watch, a large font, every value of a run at once) scroll,
 * by touch and with the crown, instead of being cut off: the column then
 * takes the scaffold's padding above and below, so its first row starts
 * under the time and its last one clears the screen's curve.
 *
 * The pager owns the horizontal drag and this column the vertical one, and
 * only the page in view has the focus the crown's input goes to (the pager
 * keeps each page in a focus group of its own), so the two do not compete.
 */
@Composable
private fun PageColumn(content: @Composable () -> Unit) {
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    ScreenScaffold(scrollState = scrollState) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .requestFocusOnHierarchyActive()
                .rotaryScrollable(
                    // A page that fits has nothing to scroll: the crown then stays silent, as before.
                    behavior = RotaryScrollableDefaults.behavior(scrollState, hapticFeedbackEnabled = scrollState.maxValue > 0),
                    focusRequester = focusRequester,
                )
                .verticalScroll(scrollState)
                .paddingWhenTallerThanTheScreen(contentPadding.calculateTopPadding(), contentPadding.calculateBottomPadding())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            content()
        }
    }
}

/**
 * [top] and [bottom] around content that is taller than the screen, nothing
 * around content that fits. Inside a vertical scroll the content is measured
 * at least as tall as the screen ([androidx.compose.ui.unit.Constraints.minHeight]),
 * so a page that fits is laid out exactly as it was before it could scroll.
 */
private fun Modifier.paddingWhenTallerThanTheScreen(top: Dp, bottom: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val padding = scrollPadding(placeable.height, constraints.minHeight, top.roundToPx(), bottom.roundToPx())
    layout(placeable.width, placeable.height + padding.top + padding.bottom) { placeable.place(0, padding.top) }
}

/** The pixels above and below a page's rows. */
internal data class ScrollPadding(val top: Int, val bottom: Int)

/** [top] and [bottom] when the rows, [contentHeight] tall, do not fit a screen of [screenHeight]; none when they do. */
internal fun scrollPadding(contentHeight: Int, screenHeight: Int, top: Int, bottom: Int): ScrollPadding =
    if (contentHeight > screenHeight) ScrollPadding(top, bottom) else ScrollPadding(0, 0)

@Composable
private fun FieldRow(icon: ImageVector, color: Color, value: String?, unit: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        MetricValueRow(value = value, unit = unit, valueStyle = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun BigValue(label: String, value: String?, unit: String) {
    Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    MetricValueRow(value = value, unit = unit, valueStyle = MaterialTheme.typography.displaySmall)
}

@WearPreviews
@Composable
private fun RecordingScreenPreview() {
    OpenWatchWearTheme {
        AppScaffold {
            RecordingScreen(
                state = SampleData.recording,
                fields = SampleData.workoutFields.getValue(ActivityType.RUN),
                unitSystem = UnitSystem.METRIC,
                zoneBasis = HeartRateZones.Basis(maxBpm = 190),
                onTogglePause = {},
                onStop = {},
            )
        }
    }
}

@WearPreviews
@Composable
private fun RecordingScreenPausedPreview() {
    OpenWatchWearTheme {
        AppScaffold {
            RecordingScreen(
                state = SampleData.recording.copy(paused = true),
                fields = SampleData.workoutFields.getValue(ActivityType.WORKOUT),
                unitSystem = UnitSystem.IMPERIAL,
                zoneBasis = HeartRateZones.Basis(maxBpm = 190),
                onTogglePause = {},
                onStop = {},
            )
        }
    }
}
