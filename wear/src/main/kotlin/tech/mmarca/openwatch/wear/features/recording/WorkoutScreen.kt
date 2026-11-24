package tech.mmarca.openwatch.wear.features.recording

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.UnitSystem
import tech.mmarca.openwatch.wear.ui.theme.WorkoutColor
import tech.mmarca.openwatch.wear.workout.WorkoutViewModel
import tech.mmarca.openwatch.wear.workout.sportName
import tech.mmarca.openwatch.wearlink.WorkoutSport

/**
 * The workout on the watch, a destination of the app's navigation: pick a
 * sport, then the running workout with its time, heart rate, distance and
 * steps, pause, resume and stop, then the summary of a saved one. The
 * recording itself runs in `WearAppService` ([WorkoutRecorder]); this screen
 * shows [WorkoutState] through its [WorkoutViewModel] and sends it actions. A
 * sport with a route asks for location first, on a watch with a GPS receiver,
 * and records without a route when it is refused. The values shown are the
 * ones the watch's sensors give ([workoutFieldsOf]). While a workout runs the screen stays on in ambient mode
 * with a dim layout of its own ([RunningWorkout]). Distances and speeds are
 * shown in [unitSystem], the one the phone chose.
 */
@Composable
internal fun WorkoutScreen(unitSystem: UnitSystem, onClose: () -> Unit) {
    val context = LocalContext.current
    val viewModel = viewModel { WorkoutViewModel.create(context) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val zoneBasis by viewModel.zoneBasis.collectAsStateWithLifecycle()
    val sensors by viewModel.sensors.collectAsStateWithLifecycle()
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val sport = WorkoutSport.fromId(pending) ?: return@rememberLauncherForActivityResult
        pending = null
        viewModel.start(sport, gps = result[Manifest.permission.ACCESS_FINE_LOCATION] == true)
    }

    val snapshot = state.snapshot
    val sport = state.sport
    when {
        state.running && sport != null && snapshot != null -> {
            val fields = workoutFieldsOf(sport, state.gps, sensors)
            // Remembered here, above the ambient layout: the page and an open "End workout?" outlive it.
            val screenState = rememberRecordingScreenState(fields)
            RunningWorkout(sport, snapshot, unitSystem) {
                RecordingScreen(
                    state = snapshot.toRecordingState(sport, sportName(sport)),
                    fields = fields,
                    unitSystem = unitSystem,
                    zoneBasis = zoneBasis,
                    onTogglePause = viewModel::togglePause,
                    onStop = viewModel::stop,
                    screenState = screenState,
                )
            }
        }
        state.finished && sport != null && snapshot != null -> WorkoutSummaryScreen(
            state = snapshot.toRecordingState(sport, sportName(sport)),
            fields = workoutFieldsOf(sport, state.gps, sensors),
            unitSystem = unitSystem,
            onDone = {
                viewModel.closeSummary()
                onClose()
            },
        )
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
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                ListHeader(modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) {
                    Text(stringResource(R.string.workout_pick))
                }
            }
            for (choice in WorkoutSport.entries) {
                workoutButton(spec, sportName(choice), icon = activityTypeOf(choice).icon) { onPick(choice) }
            }
            workoutButton(spec, R.string.workout_back, tonal = true, onClick = onBack)
        }
    }
}

private fun TransformingLazyColumnScope.workoutButton(
    spec: TransformationSpec,
    @StringRes label: Int,
    tonal: Boolean = false,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    item {
        Button(
            onClick = onClick,
            icon = icon?.let { { Icon(it, contentDescription = null, tint = WorkoutColor) } },
            label = { Text(stringResource(label)) },
            colors = if (tonal) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors(),
            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
            transformation = SurfaceTransformation(spec),
        )
    }
}
