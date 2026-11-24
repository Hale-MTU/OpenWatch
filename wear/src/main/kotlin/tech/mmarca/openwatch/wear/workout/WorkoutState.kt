package tech.mmarca.openwatch.wear.workout

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import tech.mmarca.openwatch.wearlink.WorkoutMetrics
import tech.mmarca.openwatch.wearlink.WorkoutSport

/** What the screens know of the workout: running, or the last one finished. */
data class WorkoutUiState(
    val sport: WorkoutSport? = null,
    val running: Boolean = false,
    /** The latest snapshot while running; the final one after stopping. */
    val snapshot: WorkoutSnapshot? = null,
    /** True after a stop that kept the workout, until the summary is closed. */
    val finished: Boolean = false,
    /** True when the workout records a route: decided at the start, false again if location is refused on the way. */
    val gps: Boolean = false,
    /** When the workout began: the key its rows are stored under, by which its summary reads its figures. */
    val startMillis: Long? = null,
    /** The heart rate recovery after the stop, while it is measured and after; null when none was asked for. */
    val recovery: RecoveryState? = null,
)

/**
 * The two minutes of heart rate after a workout's stop, as health-calculations
 * reads a recovery (`WorkoutMetrics.recovery`): from [stopMillis] to
 * [untilMillis], the heart rate now ([bpm]) while it runs, and the [falls]
 * once they are worked out from the stored readings.
 */
data class RecoveryState(
    val stopMillis: Long,
    val untilMillis: Long,
    val bpm: Int? = null,
    val falls: WorkoutMetrics.Recovery? = null,
) {
    /** True while the two minutes run and their falls are not worked out. */
    val measuring: Boolean get() = falls == null
}

/**
 * The workout's state, written by [WorkoutRecorder] in the service and read
 * by the screens: one holder for the process, held by `WearGraph` like
 * `WearLinkState`.
 */
class WorkoutState {
    private val flow = MutableStateFlow(WorkoutUiState())
    val state: StateFlow<WorkoutUiState> = flow

    fun update(change: (WorkoutUiState) -> WorkoutUiState) = flow.update(change)

    /** The wearer closed the summary. */
    fun clearFinished() = flow.update { if (it.running) it else WorkoutUiState() }
}
