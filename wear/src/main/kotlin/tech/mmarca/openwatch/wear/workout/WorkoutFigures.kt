package tech.mmarca.openwatch.wear.workout

import java.time.Instant
import java.time.ZoneId
import tech.mmarca.openwatch.wear.MetricStore
import tech.mmarca.openwatch.wear.TileDay
import tech.mmarca.openwatch.wear.WatchPreferences
import tech.mmarca.openwatch.wear.WearMetrics
import tech.mmarca.openwatch.wearlink.WorkoutMetrics

/**
 * A saved workout's figures on the watch, by the same [WorkoutMetrics] the
 * phone writes to Health Connect: the workout with its pauses and route as
 * the store keeps it, the heart rate the watch recorded over it, and the
 * zones and the body profile the phone sent ([settings]). Reads the store:
 * call it off the main thread.
 */
internal class WorkoutFigures(
    private val store: MetricStore,
    private val settings: () -> WatchPreferences,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val workouts = WorkoutStore(MetricStoreWorkoutRows(store))

    /** The figures of the workout that began at [startMillis]; null when the store holds none. */
    fun of(startMillis: Long): WorkoutMetrics.Summary? {
        val workout = workouts.workoutsSince(startMillis - 1, 1).items.firstOrNull()?.takeIf { it.startMillis == startMillis } ?: return null
        // A workout's heart rate is one a second, and a workout lasts at most a day.
        val beats = store.since(WearMetrics.HEART_RATE, workout.startMillis - 1, TileDay.MAX_HEART_RATE_SAMPLES_PER_DAY, workout.endMillis + 1)
            .map { WorkoutMetrics.Beat(it.epochMillis, it.bpm) }
        val prefs = settings()
        val day = Instant.ofEpochMilli(workout.startMillis).atZone(zone()).toLocalDate()
        return WorkoutMetrics.summarize(workout, beats, prefs.zoneBasis(), prefs.bodyProfile(), day)
    }
}
