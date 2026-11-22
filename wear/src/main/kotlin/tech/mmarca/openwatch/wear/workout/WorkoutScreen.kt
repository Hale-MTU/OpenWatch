package tech.mmarca.openwatch.wear.workout

import android.Manifest
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wearlink.WorkoutSport

/**
 * The workout on the watch, a destination of the app's navigation: pick a
 * sport, then the running workout with its time, heart rate, distance and
 * steps, pause, resume and stop, then the summary of a saved one. The
 * recording itself runs in `WearAppService` ([WorkoutRecorder]); this screen
 * shows [WorkoutState] through its [WorkoutViewModel] and sends it actions. A
 * sport with a route asks for location first and records without a route when
 * it is refused. While a workout runs the screen stays on in ambient mode
 * with a dim layout of its own ([RunningWorkout]).
 */
@Composable
internal fun WorkoutScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val viewModel = viewModel { WorkoutViewModel.create(context) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val sport = WorkoutSport.fromId(pending) ?: return@rememberLauncherForActivityResult
        pending = null
        viewModel.start(sport, gps = result[Manifest.permission.ACCESS_FINE_LOCATION] == true)
    }

    val snapshot = state.snapshot
    val sport = state.sport
    when {
        state.running && sport != null && snapshot != null ->
            RunningWorkout(sport, snapshot, onTogglePause = viewModel::togglePause, onStop = viewModel::stop)
        state.finished && sport != null && snapshot != null -> WorkoutSummary(sport, snapshot) {
            viewModel.closeSummary()
            onClose()
        }
        else -> SportPicker(
            onPick = { choice ->
                if (viewModel.needsLocation(choice)) {
                    pending = choice.id
                    askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                } else {
                    viewModel.start(choice)
                }
            },
            onBack = onClose,
        )
    }
}

@Composable
private fun SportPicker(onPick: (WorkoutSport) -> Unit, onBack: () -> Unit) {
    WorkoutList { spec ->
        item {
            ListHeader(modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) {
                Text(stringResource(R.string.workout_pick))
            }
        }
        for (choice in WorkoutSport.entries) workoutButton(spec, sportName(choice)) { onPick(choice) }
        workoutButton(spec, R.string.workout_back, tonal = true, onClick = onBack)
    }
}

@Composable
private fun WorkoutSummary(sport: WorkoutSport, snapshot: WorkoutSnapshot, onDone: () -> Unit) {
    WorkoutList { spec ->
        item { WorkoutLine(stringResource(R.string.workout_saved), style = MaterialTheme.typography.titleMedium) }
        item { WorkoutLine(stringResource(sportName(sport)), style = MaterialTheme.typography.bodySmall) }
        item { WorkoutLine(clock(snapshot.activeSeconds), style = MaterialTheme.typography.displaySmall) }
        for (line in valueLines(sport, snapshot)) item { WorkoutLine(stringResource(line.text, line.value)) }
        workoutButton(spec, R.string.workout_done, onClick = onDone)
    }
}

/** The interactive running workout: time, values, pause or resume, stop. */
@Composable
internal fun RunningList(sport: WorkoutSport, snapshot: WorkoutSnapshot, onTogglePause: () -> Unit, onStop: () -> Unit) {
    WorkoutList { spec ->
        item { WorkoutLine(stringResource(sportName(sport)), style = MaterialTheme.typography.bodySmall) }
        item { WorkoutLine(clock(snapshot.activeSeconds), style = MaterialTheme.typography.displaySmall) }
        if (snapshot.paused) {
            item { WorkoutLine(stringResource(R.string.workout_paused), color = MaterialTheme.colorScheme.tertiary) }
        }
        for (line in valueLines(sport, snapshot)) item { WorkoutLine(stringResource(line.text, line.value)) }
        if (snapshot.waitingForFix) {
            item { WorkoutLine(stringResource(R.string.workout_waiting_for_gps), style = MaterialTheme.typography.bodySmall) }
        }
        workoutButton(spec, if (snapshot.paused) R.string.workout_resume else R.string.workout_pause, onClick = onTogglePause)
        workoutButton(spec, R.string.workout_stop, tonal = true, onClick = onStop)
    }
}

/** A scrolling list with rotary input, the position indicator and the round screen's insets. */
@Composable
private fun WorkoutList(content: TransformingLazyColumnScope.(TransformationSpec) -> Unit) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) { content(spec) }
    }
}

private fun TransformingLazyColumnScope.workoutButton(spec: TransformationSpec, @StringRes label: Int, tonal: Boolean = false, onClick: () -> Unit) {
    item {
        Button(
            onClick = onClick,
            label = { Text(stringResource(label)) },
            colors = if (tonal) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors(),
            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
            transformation = SurfaceTransformation(spec),
        )
    }
}

@Composable
internal fun WorkoutLine(
    text: String,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(text, style = style, color = color, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
}

/** One value line: its string and the number it formats. */
internal class ValueLine(val text: Int, val value: Any)

/** The heart rate (the latest, else the average), distance, steps and height climbed that the sport has. */
internal fun valueLines(sport: WorkoutSport, snapshot: WorkoutSnapshot): List<ValueLine> = buildList {
    (snapshot.heartRateBpm ?: snapshot.averageHeartRateBpm)?.let { add(ValueLine(R.string.workout_heart_rate, it)) }
    snapshot.distanceMetres?.let { add(ValueLine(R.string.workout_distance, it / 1000.0)) }
    snapshot.steps?.takeIf { sport.steps }?.let { add(ValueLine(R.string.workout_steps, it)) }
    snapshot.ascentMetres?.takeIf { it > 0 }?.let { add(ValueLine(R.string.workout_ascent, it)) }
}

internal fun clock(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@StringRes
internal fun sportName(sport: WorkoutSport): Int = when (sport) {
    WorkoutSport.WALKING -> R.string.workout_sport_walking
    WorkoutSport.RUNNING -> R.string.workout_sport_running
    WorkoutSport.HIKING -> R.string.workout_sport_hiking
    WorkoutSport.BIKING -> R.string.workout_sport_biking
    WorkoutSport.TREADMILL -> R.string.workout_sport_treadmill
    WorkoutSport.STAIR_CLIMBING -> R.string.workout_sport_stairs
    WorkoutSport.STAIR_CLIMBING_MACHINE -> R.string.workout_sport_stair_machine
    WorkoutSport.STATIONARY_BIKE -> R.string.workout_sport_stationary_bike
    WorkoutSport.ELLIPTICAL -> R.string.workout_sport_elliptical
    WorkoutSport.ROWING_MACHINE -> R.string.workout_sport_rowing
    WorkoutSport.STRENGTH_TRAINING -> R.string.workout_sport_strength
    WorkoutSport.HIIT -> R.string.workout_sport_hiit
    WorkoutSport.YOGA -> R.string.workout_sport_yoga
    WorkoutSport.PILATES -> R.string.workout_sport_pilates
    WorkoutSport.STRETCHING -> R.string.workout_sport_stretching
    WorkoutSport.DANCING -> R.string.workout_sport_dancing
    WorkoutSport.VOLLEYBALL -> R.string.workout_sport_volleyball
    WorkoutSport.OTHER_WORKOUT -> R.string.workout_sport_other
}
