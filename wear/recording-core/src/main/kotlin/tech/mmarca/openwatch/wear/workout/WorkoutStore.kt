package tech.mmarca.openwatch.wear.workout

import tech.mmarca.openwatch.wearlink.WorkoutSport
import tech.mmarca.openwatch.wearlink.Page
import tech.mmarca.openwatch.wearlink.WearLinkWorkout
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutLines

/**
 * The rows a workout is kept in. The watch app's `MetricStore` holds them on the watch
 * (`MetricStoreWorkoutRows`); a test holds them in memory.
 */
interface WorkoutRows {
    fun putPoint(point: WearLinkWorkout.RoutePoint)
    fun points(fromMillis: Long, toMillis: Long): List<WearLinkWorkout.RoutePoint>
    fun deletePoints(fromMillis: Long, toMillis: Long)

    /** False when the write failed. */
    fun putPauses(pauses: List<WearLinkWorkout.Pause>): Boolean
    fun pauses(fromMillis: Long, toMillis: Long): List<WearLinkWorkout.Pause>

    /** False when the write failed. */
    fun putHeader(workout: WearLinkWorkout): Boolean

    /** Headers started after [sinceMillis], oldest first, at most [limit], and whether more are stored. */
    fun headers(sinceMillis: Long, limit: Int): Page<WearLinkWorkout>
    fun checkpoint(): String?
    fun setCheckpoint(text: String)
    fun clearCheckpoint()
    fun prune(nowMillis: Long)
}

/**
 * Keeps workouts: route points as they arrive, a checkpoint while one runs,
 * and the header and pauses once it is finished. A workout whose app was
 * killed is finished from its checkpoint by [recover].
 */
class WorkoutStore(private val rows: WorkoutRows) {

    fun savePoint(point: WearLinkWorkout.RoutePoint) = rows.putPoint(point)

    fun saveCheckpoint(checkpoint: WorkoutCheckpoint) = rows.setCheckpoint(checkpoint.encode())

    /**
     * Saves [workout], or drops its route when it is shorter than [MIN_SAVED_MILLIS]: a workout
     * started by a slip is not worth a record. Returns whether it was kept. One whose pauses or
     * header could not be written is not kept now and keeps its checkpoint, so [recover] saves it
     * at the next start.
     */
    fun finish(workout: WearLinkWorkout, nowMillis: Long): Boolean {
        val kept = workout.endMillis - workout.startMillis >= MIN_SAVED_MILLIS
        if (kept) {
            val saved = rows.putPauses(workout.pauses) && rows.putHeader(workout.copy(pauses = emptyList(), route = emptyList()))
            if (!saved) return false
        } else {
            rows.deletePoints(workout.startMillis, workout.endMillis)
        }
        rows.clearCheckpoint()
        rows.prune(nowMillis)
        return kept
    }

    /**
     * Saves the workout a killed app left, ending where its last checkpoint was written, and
     * returns it; null when none was running or its checkpoint cannot be read.
     */
    fun recover(nowMillis: Long): WearLinkWorkout? {
        val checkpoint = WorkoutCheckpoint.decode(rows.checkpoint())
        if (checkpoint == null) {
            rows.clearCheckpoint()
            return null
        }
        val sport = WorkoutSport.fromId(checkpoint.sportId)
        val end = WorkoutTracker.limitedEnd(checkpoint.startMillis, checkpoint.savedMillis)
        val open = checkpoint.pausedSince?.takeIf { it < end }
        val workout = WearLinkWorkout(
            startMillis = checkpoint.startMillis,
            endMillis = end,
            exerciseType = (sport ?: WorkoutSport.OTHER_WORKOUT).exerciseType,
            steps = checkpoint.steps,
            distanceMetres = checkpoint.distanceMetres,
            ascentDecimetres = checkpoint.ascentDecimetres,
            pauses = WorkoutTracker.clipPauses(
                (checkpoint.pauses + listOfNotNull(open?.let { WearLinkWorkout.Pause(it, end) })).take(WearLinkWorkoutLines.MAX_PAUSES),
                end,
            ),
            route = WorkoutTracker.thinned(rows.points(checkpoint.startMillis, end)),
        )
        return workout.takeIf { finish(it, nowMillis) }
    }

    /** The points stored for the workout that began at [startMillis], for a tracker taking over after a restart. */
    fun pointsSince(startMillis: Long, untilMillis: Long): List<WearLinkWorkout.RoutePoint> = rows.points(startMillis, untilMillis)

    /**
     * What `WO_SINCE` answers: whole workouts, each with its pauses and its
     * route, thinned to what the link carries ([WorkoutTracker.thinned]).
     */
    fun workoutsSince(sinceMillis: Long, limit: Int): Page<WearLinkWorkout> {
        val headers = rows.headers(sinceMillis, limit)
        val page = headers.items.map { header ->
            header.copy(
                pauses = rows.pauses(header.startMillis, header.endMillis),
                route = WorkoutTracker.thinned(rows.points(header.startMillis, header.endMillis)),
            )
        }
        return Page(page, headers.more)
    }

    companion object {
        /** A workout shorter than this is dropped on stopping. */
        const val MIN_SAVED_MILLIS: Long = 30_000
    }
}
