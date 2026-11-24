package tech.mmarca.openwatch.wear.features.recording

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import kotlin.math.roundToInt
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.UnitSystem
import tech.mmarca.openwatch.wear.ui.ambient.AmbientAware
import tech.mmarca.openwatch.wear.ui.components.FeetPerMeter
import tech.mmarca.openwatch.wear.workout.WorkoutSnapshot
import tech.mmarca.openwatch.wear.workout.sportName
import tech.mmarca.openwatch.wearlink.WorkoutSport

/**
 * The running workout, which stays on the screen in ambient mode
 * ([AmbientAware], shared with a running spot measurement): the
 * [interactive] layout while the screen is awake, a dim one of its own in
 * ambient. The ongoing activity (the watch face pill, `WearAppService`)
 * keeps the system from going back to the watch face while the workout
 * lasts. The ambient layout shows nothing that would be stale between the
 * system's once-a-minute updates: the time in whole minutes, as of the last
 * ambient tick, and dashes where the heart rate and the distance would be.
 * Its units are [unitSystem]'s, as on the interactive pages.
 */
@Composable
internal fun RunningWorkout(
    sport: WorkoutSport,
    snapshot: WorkoutSnapshot,
    unitSystem: UnitSystem,
    interactive: @Composable () -> Unit,
) {
    AmbientAware(latest = snapshot, ambient = { ticked -> AmbientWorkout(sport, ticked, unitSystem) }, interactive = interactive)
}

/** The ambient layout: sport, minutes, paused, and placeholders for the values that change by the second. */
@Composable
private fun AmbientWorkout(sport: WorkoutSport, snapshot: WorkoutSnapshot, unitSystem: UnitSystem) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    ScreenScaffold { contentPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            WorkoutLine(stringResource(sportName(sport)), style = MaterialTheme.typography.bodySmall, color = dim)
            WorkoutLine(
                ambientClock(snapshot.activeSeconds),
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Light),
                color = dim,
            )
            if (snapshot.paused) WorkoutLine(stringResource(R.string.recording_paused), color = dim)
            for (line in ambientLines(sport, snapshot, unitSystem)) {
                val value = line.value
                WorkoutLine(if (value == null) stringResource(line.text) else stringResource(line.text, value), color = dim)
            }
        }
    }
}

@Composable
private fun WorkoutLine(text: String, style: TextStyle = MaterialTheme.typography.bodyMedium, color: Color) {
    Text(text, style = style, color = color, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
}

/** One line of the ambient layout: its string, and the number it formats, or null for a string that takes none. */
internal data class AmbientLine(@param:StringRes val text: Int, val value: Int? = null)

/**
 * The values the workout has, as the ambient layout shows them: the heart
 * rate and the distance, which change by the second, as dashes with their
 * unit, then the steps and the height climbed as of the last tick, the
 * distance's unit and the height in [unitSystem].
 */
internal fun ambientLines(sport: WorkoutSport, snapshot: WorkoutSnapshot, unitSystem: UnitSystem): List<AmbientLine> = buildList {
    val imperial = unitSystem == UnitSystem.IMPERIAL
    if (snapshot.heartRateBpm != null || snapshot.averageHeartRateBpm != null) {
        add(AmbientLine(R.string.workout_heart_rate_placeholder))
    }
    if (snapshot.distanceMetres != null) {
        add(AmbientLine(if (imperial) R.string.workout_distance_placeholder_mi else R.string.workout_distance_placeholder))
    }
    snapshot.steps?.takeIf { sport.steps }?.let { add(AmbientLine(R.string.workout_steps, it)) }
    snapshot.ascentMetres?.takeIf { it > 0 }?.let { metres ->
        add(
            if (imperial) {
                AmbientLine(R.string.workout_ascent_ft, (metres * FeetPerMeter).roundToInt())
            } else {
                AmbientLine(R.string.workout_ascent, metres)
            },
        )
    }
}

/** Hours and minutes: in ambient mode the screen changes once a minute, so seconds would be wrong. */
internal fun ambientClock(seconds: Long): String = "%d:%02d".format(seconds / 3600, seconds % 3600 / 60)
