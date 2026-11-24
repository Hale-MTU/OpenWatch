package tech.mmarca.openwatch.wear.workout

import tech.mmarca.openwatch.wear.MetricStore
import tech.mmarca.openwatch.wear.WearMetrics
import tech.mmarca.openwatch.wearlink.Page
import tech.mmarca.openwatch.wearlink.WearLinkWorkout
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutLines

/** [WorkoutRows] on the watch's [MetricStore]. */
class MetricStoreWorkoutRows(private val store: MetricStore) : WorkoutRows {
    override fun putPoint(point: WearLinkWorkout.RoutePoint) {
        store.put(WearMetrics.WORKOUT_POINTS, point)
    }

    /** Every point stored for the workout, up to a day's worth; the caller thins them for the link. */
    override fun points(fromMillis: Long, toMillis: Long): List<WearLinkWorkout.RoutePoint> =
        store.since(WearMetrics.WORKOUT_POINTS, fromMillis - 1, WorkoutTracker.MAX_STORED_POINTS, toMillis + 1)

    override fun deletePoints(fromMillis: Long, toMillis: Long) {
        store.deleteRange(WearMetrics.WORKOUT_POINTS, fromMillis, toMillis)
    }

    override fun putPauses(pauses: List<WearLinkWorkout.Pause>): Boolean = store.putAll(WearMetrics.WORKOUT_PAUSES, pauses)

    override fun pauses(fromMillis: Long, toMillis: Long): List<WearLinkWorkout.Pause> =
        store.since(WearMetrics.WORKOUT_PAUSES, fromMillis - 1, WearLinkWorkoutLines.MAX_PAUSES, toMillis + 1)

    override fun putHeader(workout: WearLinkWorkout): Boolean = store.put(WearMetrics.WORKOUTS, workout)

    override fun headers(sinceMillis: Long, limit: Int): Page<WearLinkWorkout> = store.page(WearMetrics.WORKOUTS, sinceMillis, limit)

    override fun checkpoint(): String? = store.state(CHECKPOINT_KEY)

    override fun setCheckpoint(text: String) {
        store.setState(CHECKPOINT_KEY, text)
    }

    override fun clearCheckpoint() {
        store.clearState(CHECKPOINT_KEY)
    }

    override fun prune(nowMillis: Long) {
        store.prune(WearMetrics.WORKOUTS, nowMillis)
        store.prune(WearMetrics.WORKOUT_PAUSES, nowMillis)
        store.prune(WearMetrics.WORKOUT_POINTS, nowMillis)
    }

    private companion object {
        const val CHECKPOINT_KEY = "workout"
    }
}
