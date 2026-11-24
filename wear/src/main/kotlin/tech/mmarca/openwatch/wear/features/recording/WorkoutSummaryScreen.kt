package tech.mmarca.openwatch.wear.features.recording

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.UnitSystem
import tech.mmarca.openwatch.wear.MetricTile
import tech.mmarca.openwatch.wear.ui.components.MetricValueRow
import tech.mmarca.openwatch.wear.ui.components.formatCount
import tech.mmarca.openwatch.wear.ui.components.formatMetricValue
import tech.mmarca.openwatch.wear.ui.components.metricUnitLabel
import tech.mmarca.openwatch.wear.ui.preview.SampleData
import tech.mmarca.openwatch.wear.ui.preview.WearPreviews
import tech.mmarca.openwatch.wear.ui.theme.OpenWatchWearTheme
import tech.mmarca.openwatch.wear.ui.theme.WorkoutColor

/**
 * What a finished, saved workout came to. Rows the workout did not report are
 * left out; pace and speed are the workout's average.
 */
@Composable
fun WorkoutSummaryScreen(
    state: RecordingUiState,
    fields: Set<WorkoutField>,
    unitSystem: UnitSystem,
    onDone: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(
        scrollState = listState,
        edgeButton = { EdgeButton(onClick = onDone) { Text(stringResource(R.string.workout_done)) } },
    ) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                ListHeader {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(state.activityType.icon, contentDescription = null, tint = WorkoutColor)
                        Text(
                            text = stringResource(state.title ?: state.activityType.label),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
            item {
                Text(
                    text = stringResource(R.string.workout_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                SummaryRow(
                    label = stringResource(R.string.summary_duration),
                    value = DateUtils.formatElapsedTime(state.elapsedSeconds),
                )
            }
            if (WorkoutField.DISTANCE in fields && state.distanceMeters != null) {
                item {
                    SummaryRow(
                        label = stringResource(R.string.metric_distance),
                        value = formatWorkoutDistance(state.distanceMeters, unitSystem),
                        unit = stringResource(metricUnitLabel(MetricTile.DISTANCE, unitSystem)),
                    )
                }
            }
            val averageSpeed = state.averageSpeedMetersPerSecond
            if (WorkoutField.PACE in fields && averageSpeed != null) {
                item {
                    SummaryRow(
                        label = stringResource(R.string.recording_pace),
                        value = formatPace(averageSpeed, unitSystem),
                        unit = stringResource(paceUnit(unitSystem)),
                    )
                }
            }
            if (WorkoutField.SPEED in fields && averageSpeed != null) {
                item {
                    SummaryRow(
                        label = stringResource(R.string.recording_speed),
                        value = formatSpeed(averageSpeed, unitSystem),
                        unit = stringResource(speedUnit(unitSystem)),
                    )
                }
            }
            if (WorkoutField.STEPS in fields && state.steps != null) {
                item {
                    SummaryRow(
                        label = stringResource(R.string.metric_steps),
                        value = formatCount(state.steps),
                        unit = stringResource(R.string.unit_steps),
                    )
                }
            }
            if (WorkoutField.HEART_RATE in fields && state.averageHeartRateBpm != null) {
                item {
                    SummaryRow(
                        label = stringResource(R.string.summary_average_heart_rate),
                        value = formatCount(state.averageHeartRateBpm),
                        unit = stringResource(R.string.unit_bpm),
                    )
                }
            }
            if (WorkoutField.ELEVATION in fields && state.elevationGainMeters != null) {
                item {
                    SummaryRow(
                        label = stringResource(R.string.metric_elevation),
                        value = formatMetricValue(MetricTile.ELEVATION, state.elevationGainMeters.toDouble(), unitSystem),
                        unit = stringResource(metricUnitLabel(MetricTile.ELEVATION, unitSystem)),
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String?, unit: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MetricValueRow(value = value, unit = unit, valueStyle = MaterialTheme.typography.titleMedium)
    }
}

@WearPreviews
@Composable
private fun WorkoutSummaryScreenPreview() {
    OpenWatchWearTheme {
        AppScaffold {
            WorkoutSummaryScreen(
                state = SampleData.recording,
                fields = SampleData.workoutFields.getValue(ActivityType.RUN),
                unitSystem = UnitSystem.METRIC,
                onDone = {},
            )
        }
    }
}
