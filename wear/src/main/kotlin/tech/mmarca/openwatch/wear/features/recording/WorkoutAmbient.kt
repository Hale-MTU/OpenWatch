package tech.mmarca.openwatch.wear.features.recording

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.AmbientMode
import androidx.wear.compose.foundation.AmbientModeManager
import androidx.wear.compose.foundation.AmbientTickEffect
import androidx.wear.compose.foundation.LocalAmbientModeManager
import androidx.wear.compose.foundation.rememberAmbientModeManager
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import kotlin.math.roundToInt
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.UnitSystem
import tech.mmarca.openwatch.wear.ui.components.FeetPerMeter
import tech.mmarca.openwatch.wear.workout.WorkoutSnapshot
import tech.mmarca.openwatch.wear.workout.sportName
import tech.mmarca.openwatch.wearlink.WorkoutSport

/**
 * The running workout, which stays on the screen in ambient mode: the
 * [interactive] layout while the screen is awake, a dim one of its own in ambient.
 * The interactive layout leaves composition meanwhile, so what it must find
 * again (its page, an open dialog) is remembered by the caller, above this.
 *
 * Wear OS 6 keeps an app that targets API 36 visible in ambient mode, dimmed,
 * on every screen. A screen that observes the ambient state draws its own
 * low-power layout instead: this one does, through the Wear Compose
 * [AmbientModeManager], while a workout runs, and only then. The ongoing
 * activity (the watch face pill, `WearAppService`) keeps the system from
 * going back to the watch face while the workout lasts. On a watch without
 * the Wear SDK the manager is not there; the system then dims the screen or
 * shows the watch face, as before, and nothing else changes.
 *
 * The ambient layout follows the always-on guidance: black, no buttons,
 * nothing bright, and nothing that would be stale between the system's
 * once-a-minute updates: the time in whole minutes, as of the last ambient
 * tick, and dashes where the heart rate and the distance would be. Its units
 * are [unitSystem]'s, as on the interactive pages.
 */
@Composable
internal fun RunningWorkout(
    sport: WorkoutSport,
    snapshot: WorkoutSnapshot,
    unitSystem: UnitSystem,
    interactive: @Composable () -> Unit,
) {
    val manager = rememberAmbientModeManagerIfSupported()
    if (manager == null) {
        interactive()
        return
    }
    CompositionLocalProvider(LocalAmbientModeManager provides manager) {
        val latest by rememberUpdatedState(snapshot)
        // In ambient mode the screen shows the snapshot of the last tick, not the second-by-second one.
        var ticked by remember { mutableStateOf(snapshot) }
        manager.AmbientTickEffect { ticked = latest }
        val ambient = manager.currentAmbientMode is AmbientMode.Ambient
        LaunchedEffect(ambient) { if (ambient) ticked = latest }
        if (ambient) AmbientWorkout(sport, ticked, unitSystem) else interactive()
    }
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

/**
 * The activity's ambient mode manager where the watch has the Wear SDK's
 * ambient service it rests on (a system library of recent Wear OS
 * releases); null elsewhere, where creating it would throw, since Wear
 * Compose takes the service with no fall-back. Being composed turns the
 * ambient handling on.
 */
@Composable
private fun rememberAmbientModeManagerIfSupported(): AmbientModeManager? {
    val context = LocalContext.current
    val supported = remember { hasWearSdkAmbient(context) }
    return if (supported) rememberAmbientModeManager() else null
}

/** Whether `Sdk.getWearManager(context, AmbientManager::class.java)` answers, asked by reflection: the app has no stub of the library. */
private fun hasWearSdkAmbient(context: Context): Boolean = try {
    val loader = context.classLoader
    val manager = Class.forName(WEAR_SDK_AMBIENT_MANAGER, false, loader)
    val sdk = Class.forName(WEAR_SDK, false, loader)
    sdk.getMethod("getWearManager", Context::class.java, Class::class.java).invoke(null, context.applicationContext, manager) != null
} catch (_: ReflectiveOperationException) {
    false
} catch (_: LinkageError) {
    false
} catch (_: RuntimeException) {
    false
}

private const val WEAR_SDK = "com.google.wear.Sdk"
private const val WEAR_SDK_AMBIENT_MANAGER = "com.google.wear.services.ambient.AmbientManager"
