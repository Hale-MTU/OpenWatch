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
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import tech.mmarca.healthcalculations.insights.HeartRateRecoveryQuality
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
import tech.mmarca.openwatch.wear.ui.theme.OpenWatchWearTheme
import tech.mmarca.openwatch.wear.ui.theme.WorkoutColor
import tech.mmarca.openwatch.wearlink.WorkoutMetrics
import kotlin.math.roundToInt

/**
 * What a finished, saved workout came to. Rows the workout did not report are
 * left out; pace and speed are the workout's average. Below them the
 * workout's [figures], the same the phone writes to Health Connect
 * ([WorkoutMetrics]; null until read), and the heart rate's [recovery]
 * after the stop where it was measured.
 */
@Composable
fun WorkoutSummaryScreen(
    state: RecordingUiState,
    fields: Set<WorkoutField>,
    unitSystem: UnitSystem,
    onDone: () -> Unit,
    figures: WorkoutMetrics.Summary? = null,
    recovery: WorkoutMetrics.Recovery? = null,
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
            // The figures' mean once read, the phone's number: each pair of readings for the time between them.
            val averageBpm = figures?.averageBpm ?: state.averageHeartRateBpm
            if (WorkoutField.HEART_RATE in fields && averageBpm != null) {
                item {
                    SummaryRow(
                        label = stringResource(R.string.summary_average_heart_rate),
                        value = formatCount(averageBpm),
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
            if (figures != null) figureRows(figures, unitSystem)
            if (recovery != null) recoveryRows(recovery)
        }
    }
}

/**
 * The workout's figures: the highest heart rate, the time in each zone (when any counted),
 * the training load, the energy above rest (else the whole), the kilometre splits and the
 * VO2max, each where it has a value. The pace is the recorded rows' already.
 */
private fun TransformingLazyColumnScope.figureRows(figures: WorkoutMetrics.Summary, unitSystem: UnitSystem) {
    figures.maxBpm?.let { bpm ->
        item { SummaryRow(stringResource(R.string.summary_max_heart_rate), formatCount(bpm), stringResource(R.string.unit_bpm)) }
    }
    if (figures.zoneSeconds.any { it > 0 }) {
        figures.zoneSeconds.forEachIndexed { zone, seconds ->
            item {
                SummaryRow(
                    label = stringResource(R.string.zone_label, zone + 1, stringResource(HeartZoneDisplay.labels[zone])),
                    value = DateUtils.formatElapsedTime(seconds),
                )
            }
        }
    }
    figures.trimp?.let { trimp -> item { SummaryRow(stringResource(R.string.summary_trimp), formatCount(trimp.roundToInt())) } }
    val active = figures.activeKcal
    val energy = active ?: figures.totalKcal
    if (energy != null) {
        item {
            SummaryRow(
                label = stringResource(if (active != null) R.string.summary_active_energy else R.string.summary_total_energy),
                value = formatCount(energy.roundToInt()),
                unit = stringResource(R.string.unit_kcal),
            )
        }
    }
    for (split in figures.splits) {
        item { SummaryRow(stringResource(R.string.summary_split, split.index + 1), DateUtils.formatElapsedTime(split.activeSeconds)) }
    }
    figures.vo2Max?.let { vo2 ->
        item {
            SummaryRow(
                label = stringResource(R.string.metric_vo2_max),
                value = formatMetricValue(MetricTile.VO2_MAX, vo2, unitSystem),
                unit = stringResource(metricUnitLabel(MetricTile.VO2_MAX, unitSystem)),
            )
        }
    }
}

/**
 * How far the heart rate fell one and two minutes after the stop, and under the falls, as a
 * line of its own, the reading's verdict where it is less than clean: approximate, not
 * comparable with other recoveries (the effort was not near-maximal), or not usable, which
 * says why there is no fall.
 */
private fun TransformingLazyColumnScope.recoveryRows(recovery: WorkoutMetrics.Recovery) {
    recovery.oneMinuteBpm?.let { fall ->
        item { SummaryRow(stringResource(R.string.recovery_one_minute), "−${formatCount(fall)}", stringResource(R.string.unit_bpm)) }
    }
    recovery.twoMinutesBpm?.let { fall ->
        item { SummaryRow(stringResource(R.string.recovery_two_minutes), "−${formatCount(fall)}", stringResource(R.string.unit_bpm)) }
    }
    recoveryVerdict(recovery.quality)?.let { verdict ->
        item {
            Text(
                text = stringResource(verdict),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
            )
        }
    }
}

/** The verdict's line: none for a clean reading or one without data, a word for the others. */
internal fun recoveryVerdict(quality: HeartRateRecoveryQuality): Int? = when (quality) {
    HeartRateRecoveryQuality.APPROXIMATE -> R.string.recovery_approximate
    HeartRateRecoveryQuality.NOT_COMPARABLE -> R.string.recovery_not_comparable
    HeartRateRecoveryQuality.INVALID -> R.string.recovery_invalid
    HeartRateRecoveryQuality.CLEAN, HeartRateRecoveryQuality.NO_DATA -> null
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
        // The label takes what the value leaves and wraps; the value stays on one line.
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
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
                figures = WorkoutMetrics.Summary(
                    averageBpm = 148, maxBpm = 171, zoneSeconds = listOf(120L, 480L, 900L, 300L, 0L), trimp = 61.4,
                    activeKcal = 312.0, totalKcal = 371.0,
                    splits = listOf(WorkoutMetrics.Split(0, 0, 330_000, 330), WorkoutMetrics.Split(1, 330_000, 655_000, 325)),
                    paceSecondsPerKm = 327.0, vo2Max = 47.8,
                ),
                recovery = WorkoutMetrics.Recovery(
                    oneMinuteBpm = 27,
                    twoMinutesBpm = 44,
                    peakBpm = 171,
                    quality = HeartRateRecoveryQuality.NOT_COMPARABLE,
                ),
            )
        }
    }
}
