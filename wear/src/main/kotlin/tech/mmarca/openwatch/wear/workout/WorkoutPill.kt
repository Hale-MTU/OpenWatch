package tech.mmarca.openwatch.wear.workout

import tech.mmarca.openwatch.wearlink.WorkoutSport

/**
 * What the watch face pill shows. It changes only when a workout starts,
 * pauses, resumes or ends, and when the heart rate recovery after it starts
 * or ends; the stopwatch counts by itself in between.
 */
data class WorkoutPillKey(val sport: WorkoutSport, val paused: Boolean, val recovering: Boolean = false)

/** Null when no workout is running and no recovery is measured: then there is no pill. */
fun WorkoutUiState.pillKey(): WorkoutPillKey? {
    val sport = sport ?: return null
    if (recovery?.measuring == true) return WorkoutPillKey(sport, paused = false, recovering = true)
    if (!running) return null
    return WorkoutPillKey(sport, paused = snapshot?.paused == true)
}

/**
 * The time the pill's stopwatch counts from, on the `elapsedRealtime` clock.
 * Null while paused or during the recovery: the pill then says so in text and
 * the clock stands still. [activeSeconds] is the time without pauses, so a
 * resume continues from it.
 */
fun stopwatchBaseMillis(key: WorkoutPillKey, activeSeconds: Long, elapsedRealtimeMillis: Long): Long? =
    if (key.paused || key.recovering) null else elapsedRealtimeMillis - activeSeconds * 1000
