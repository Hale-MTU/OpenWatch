package tech.mmarca.openwatch.wear.workout

import android.content.Context
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import tech.mmarca.openwatch.wear.WearAppService
import tech.mmarca.openwatch.wear.WearPermissions
import tech.mmarca.openwatch.wear.wearGraph
import tech.mmarca.openwatch.wearlink.WorkoutSport

/** What the workout screen asks of the recorder: `WearAppService` on the watch, a fake in a test. */
internal interface WorkoutControl {
    /** Whether the location grant is in, which a sport with a route asks for first. */
    fun hasLocation(): Boolean

    fun start(sport: WorkoutSport, gps: Boolean)

    fun pause()

    fun resume()

    fun stop()
}

/**
 * The workout screen's state and actions: the [WorkoutState] the recorder
 * publishes once a second while a workout runs, and the actions that go to
 * the recorder in the service. The recording itself never depends on the
 * screen: closing it changes nothing.
 */
internal class WorkoutViewModel(
    private val workout: WorkoutState,
    private val control: WorkoutControl,
    scope: CoroutineScope,
) : ViewModel(scope) {

    val state: StateFlow<WorkoutUiState> = workout.state

    /** True when [sport] records a route and the grant is not in: the screen asks for it, then calls [start]. */
    fun needsLocation(sport: WorkoutSport): Boolean = sport.gps && !control.hasLocation()

    /** Starts [sport]; [gps] is false when the wearer declined location, and the workout then has no route. */
    fun start(sport: WorkoutSport, gps: Boolean = sport.gps) = control.start(sport, gps && sport.gps)

    /** Pauses a running workout, or resumes a paused one. */
    fun togglePause() {
        val snapshot = workout.state.value.snapshot ?: return
        if (!workout.state.value.running) return
        if (snapshot.paused) control.resume() else control.pause()
    }

    fun stop() {
        if (workout.state.value.running) control.stop()
    }

    /** The wearer closed the summary of a saved workout. */
    fun closeSummary() = workout.clearFinished()

    companion object {
        /** The screen's ViewModel over the process's graph and the service. */
        fun create(context: Context): WorkoutViewModel {
            val app = context.applicationContext
            return WorkoutViewModel(app.wearGraph.workoutState, ServiceWorkoutControl(app), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        }
    }
}

/** [WorkoutControl] through `WearAppService`, which runs the [WorkoutRecorder]. */
private class ServiceWorkoutControl(private val context: Context) : WorkoutControl {
    override fun hasLocation(): Boolean = WearPermissions.hasLocation(context)

    override fun start(sport: WorkoutSport, gps: Boolean) = WearAppService.startWorkout(context, sport, gps)

    override fun pause() = WearAppService.pauseWorkout(context)

    override fun resume() = WearAppService.resumeWorkout(context)

    override fun stop() = WearAppService.stopWorkout(context)
}
