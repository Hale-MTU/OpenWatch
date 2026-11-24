package tech.mmarca.openwatch.wear.features.recording

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
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

private enum class WorkoutPage { CONTROLS, OVERVIEW, HEART, PACE }

/**
 * A running workout, laid out like the watch makers' own: the overview in
 * the middle, controls one swipe to the left, heart rate zones and pace to
 * the right. Only pages and values the workout reports in [fields] are shown.
 * The zones are those of [zoneBasis], the maximum and resting rate the phone
 * sent. The screen is not held on: in ambient mode `RunningWorkout` draws its
 * own dim layout instead of this one.
 */
@Composable
fun RecordingScreen(
    state: RecordingUiState,
    fields: Set<WorkoutField>,
    unitSystem: UnitSystem,
    zoneBasis: HeartRateZones.Basis,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
) {
    val pages = remember(fields) {
        buildList {
            add(WorkoutPage.CONTROLS)
            add(WorkoutPage.OVERVIEW)
            if (WorkoutField.HEART_RATE in fields) add(WorkoutPage.HEART)
            if (fields.any { it == WorkoutField.PACE || it == WorkoutField.SPEED || it == WorkoutField.ELEVATION }) {
                add(WorkoutPage.PACE)
            }
        }
    }
    val pagerState = rememberPagerState(initialPage = pages.indexOf(WorkoutPage.OVERVIEW)) { pages.size }
    var confirmStop by rememberSaveable { mutableStateOf(false) }

    HorizontalPagerScaffold(pagerState = pagerState) {
        HorizontalPager(state = pagerState) { page ->
            ScreenScaffold {
                when (pages[page]) {
                    WorkoutPage.CONTROLS -> ControlsPage(state, onTogglePause, onStop = { confirmStop = true })
                    WorkoutPage.OVERVIEW -> OverviewPage(state, fields, unitSystem, zoneBasis)
                    WorkoutPage.HEART -> HeartPage(state, zoneBasis)
                    WorkoutPage.PACE -> PacePage(state, fields, unitSystem)
                }
            }
        }
    }

    AlertDialog(
        visible = confirmStop,
        onDismissRequest = { confirmStop = false },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    confirmStop = false
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
    zoneBasis: HeartRateZones.Basis,
) {
    val zone = state.heartRateBpm?.let { HeartZoneDisplay.zoneOf(it, zoneBasis) }
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
private fun HeartPage(state: RecordingUiState, zoneBasis: HeartRateZones.Basis) {
    val bpm = state.heartRateBpm
    val zone = bpm?.let { HeartZoneDisplay.zoneOf(it, zoneBasis) }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        HeartZoneArc(
            zone = zone,
            progress = bpm?.let { HeartZoneDisplay.arcProgress(it, zoneBasis) } ?: 0f,
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

@Composable
private fun ControlsPage(state: RecordingUiState, onTogglePause: () -> Unit, onStop: () -> Unit) {
    PageColumn {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledTonalIconButton(onClick = onTogglePause, modifier = Modifier.size(56.dp)) {
                    Icon(if (state.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, contentDescription = null)
                }
                Text(
                    text = stringResource(if (state.paused) R.string.workout_resume else R.string.workout_pause),
                    style = MaterialTheme.typography.labelSmall,
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
                    Icon(Icons.Outlined.Stop, contentDescription = null)
                }
                Text(text = stringResource(R.string.workout_stop), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun PageColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        content()
    }
}

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
