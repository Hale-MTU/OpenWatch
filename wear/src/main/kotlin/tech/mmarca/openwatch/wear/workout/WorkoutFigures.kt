package tech.mmarca.openwatch.wear.workout

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import tech.mmarca.healthcalculations.preferences.BiologicalSex
import tech.mmarca.healthcalculations.preferences.BodyProfile
import tech.mmarca.openwatch.wear.MetricStore
import tech.mmarca.openwatch.wear.WearMetrics
import tech.mmarca.openwatch.wear.WearSettings
import tech.mmarca.openwatch.wear.features.metric.ZoneBasis
import tech.mmarca.openwatch.wearlink.WorkoutMetrics

/**
 * A finished workout's figures on the watch, by the same [WorkoutMetrics] the
 * phone writes to Health Connect: the saved workout with its pauses and route,
 * the heart rate the watch kept, and the profile and zones the phone sent.
 * Reads the store: call it off the main thread.
 */
internal object WorkoutFigures {

    fun latest(context: Context, zones: ZoneBasis?): WorkoutMetrics.Summary? {
        val metricStore = MetricStore(context)
        val workout = WorkoutStore(MetricStoreWorkoutRows(metricStore))
            .workoutsSince(System.currentTimeMillis() - LOOKBACK_MILLIS, MAX_WORKOUTS).items.lastOrNull() ?: return null
        val beats = metricStore.since(WearMetrics.HEART_RATE, workout.startMillis - 1, MAX_READINGS, workout.endMillis + 1)
            .map { WorkoutMetrics.Beat(it.epochMillis, it.bpm) }
        return WorkoutMetrics.summarize(workout, beats, zones, profile(context), dayOf(workout.startMillis))
    }

    /** The body profile the phone sent ([WearSettings]): what the energy and the recovery's verdict read. */
    fun profile(context: Context): BodyProfile {
        val settings = WearSettings.read(context)
        return BodyProfile(
            birthYear = settings.birthYear,
            weightKg = settings.weightHectograms?.div(10.0),
            heightCm = settings.heightCm?.toDouble(),
            sex = settings.female?.let { if (it) BiologicalSex.FEMALE else BiologicalSex.MALE },
        )
    }

    /** The day [epochMillis] falls on here: the day an age is read on, as the phone reads it. */
    fun dayOf(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()

    private const val LOOKBACK_MILLIS = 2L * 24 * 60 * 60 * 1000
    private const val MAX_WORKOUTS = 50

    /** A day at one reading a second. */
    private const val MAX_READINGS = 90_000
}
