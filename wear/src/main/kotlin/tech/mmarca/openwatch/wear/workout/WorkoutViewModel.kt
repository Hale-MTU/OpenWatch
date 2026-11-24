package tech.mmarca.openwatch.wear.workout

import android.content.Context
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tech.mmarca.healthcalculations.insights.HeartRateZones
import tech.mmarca.openwatch.wear.WatchPreferences
import tech.mmarca.openwatch.wear.WatchSensors
import tech.mmarca.openwatch.wear.WearAppService
import tech.mmarca.openwatch.wear.WearPermissions
import tech.mmarca.openwatch.wear.wearGraph
import tech.mmarca.openwatch.wearlink.WorkoutMetrics
import tech.mmarca.openwatch.wearlink.WorkoutSport

/** What the workout screen asks of the recorder: `WearAppService` on the watch, a fake in a test. */
internal interface WorkoutControl {
    /** Whether the location grant is in, which a sport with a route asks for first. */
    fun hasLocation(): Boolean

    fun start(sport: WorkoutSport, gps: Boolean)

    fun pause()

    fun resume()

    /** Ends the workout; [recovery] says what becomes of its heart rate recovery. */
    fun stop(recovery: RecoveryAtStop)
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
    /** The watch's settings as they change (`WearGraph.settings`). */
    settings: Flow<WatchPreferences>,
    scope: CoroutineScope,
    /** What the watch has to measure with (`WearGraph.sensors`), read once. */
    sensors: Flow<WatchSensors> = emptyFlow(),
    /** A saved workout's figures by its start ([WorkoutFigures]); reads the store, so it runs on [io]. */
    figuresOf: (Long) -> WorkoutMetrics.Summary? = { null },
    io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel(scope) {

    val state: StateFlow<WorkoutUiState> = workout.state

    /**
     * What the heart rate zones rest on: the maximum and resting rate in the
     * settings, followed as the phone changes them, during a workout too.
     * Null until the settings are read, and the screen then draws no zone.
     */
    val zoneBasis: StateFlow<HeartRateZones.Basis?> =
        settings.map { it.zoneBasis() }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * What the watch has to measure with: the fields a workout shows follow
     * it. Null until read, and the screen then shows every field the sport has.
     */
    val sensors: StateFlow<WatchSensors?> = sensors.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * The figures of the workout the summary shows: read once it is saved, off the main
     * thread. Null while it runs, until they are read, and for one the store does not hold.
     */
    val figures: StateFlow<WorkoutMetrics.Summary?> = workout.state
        .map { it.startMillis.takeIf { _ -> it.finished } }
        .distinctUntilChanged()
        .map { start -> start?.let(figuresOf) }
        .flowOn(io)
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * True when [sport] records a route on a watch with a GPS receiver and the
     * grant is not in: the screen asks for it, then calls [start].
     */
    fun needsLocation(sport: WorkoutSport): Boolean = recordsRoute(sport) && !control.hasLocation()

    /**
     * Starts [sport]; [gps] is false when the wearer declined location, and
     * the workout then has no route, as on a watch without a GPS receiver.
     */
    fun start(sport: WorkoutSport, gps: Boolean = sport.gps) = control.start(sport, gps && recordsRoute(sport))

    /** A watch whose sensors are not read yet is taken to have a GPS receiver: the recorder finds out. */
    private fun recordsRoute(sport: WorkoutSport): Boolean = sport.gps && sensors.value?.gps != false

    /** Pauses a running workout, or resumes a paused one. */
    fun togglePause() {
        val snapshot = workout.state.value.snapshot ?: return
        if (!workout.state.value.running) return
        if (snapshot.paused) control.resume() else control.pause()
    }

    /**
     * What a stop now would do about the heart rate recovery ([RecoveryPlan]): read it from the
     * rest before the stop, ask whether to measure it live, or nothing, without zones or with a
     * heart rate too low for it.
     */
    fun recoveryAtStop(): RecoveryAtStop {
        val snapshot = workout.state.value.snapshot ?: return RecoveryAtStop.NONE
        return RecoveryPlan.atStop(snapshot.rest, snapshot.heartRateBpm, zoneBasis.value)
    }

    /** Ends the running workout; [recovery] says what becomes of its heart rate recovery. */
    fun stop(recovery: RecoveryAtStop = RecoveryAtStop.NONE) {
        if (workout.state.value.running) control.stop(recovery)
    }

    /** The wearer closed the summary of a saved workout. */
    fun closeSummary() = workout.clearFinished()

    companion object {
        /** The screen's ViewModel over the process's graph and the service. */
        fun create(context: Context): WorkoutViewModel {
            val app = context.applicationContext
            return WorkoutViewModel(
                app.wearGraph.workoutState,
                ServiceWorkoutControl(app),
                // The first read of the settings loads their file: off the main thread.
                flow { emitAll(app.wearGraph.settings) }.flowOn(Dispatchers.IO),
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                // So does the sensor list.
                flow { emit(app.wearGraph.sensors) }.flowOn(Dispatchers.IO),
                figuresOf = WorkoutFigures(app.wearGraph.store, settings = { app.wearGraph.settings.value })::of,
            )
        }
    }
}

/** [WorkoutControl] through `WearAppService`, which runs the [WorkoutRecorder]. */
private class ServiceWorkoutControl(private val context: Context) : WorkoutControl {
    override fun hasLocation(): Boolean = WearPermissions.hasLocation(context)

    override fun start(sport: WorkoutSport, gps: Boolean) = WearAppService.startWorkout(context, sport, gps)

    override fun pause() = WearAppService.pauseWorkout(context)

    override fun resume() = WearAppService.resumeWorkout(context)

    override fun stop(recovery: RecoveryAtStop) = WearAppService.stopWorkout(context, recovery)
}
