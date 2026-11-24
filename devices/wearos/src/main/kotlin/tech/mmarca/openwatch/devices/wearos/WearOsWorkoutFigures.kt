package tech.mmarca.openwatch.devices.wearos

import java.time.Duration
import java.time.ZoneId
import tech.mmarca.healthcalculations.insights.HeartRateZoneBasis
import tech.mmarca.healthcalculations.insights.observedMaxHeartRateLookbackDays
import tech.mmarca.healthcalculations.preferences.BodyProfile
import tech.mmarca.openwatch.healthconnect.HealthConnectReads
import tech.mmarca.openwatch.wearlink.WorkoutMetrics

/**
 * Works out a workout's figures on the phone ([WorkoutMetrics.Summary], the
 * ones the watch's summary shows): from the watch's own heart rate in Health
 * Connect over the session ([watchBeats]; the sync writes the readings before
 * it pulls the workouts), the body profile, and the zones' basis as [WearOsZoneSync] hands
 * it to the watch ([HeartRateZoneBasis]), each read through [HealthConnectReads].
 * The heart rate recovery after the stop is read too where the watch measured
 * one: the two minutes after it hold a reading a second.
 */
internal class WearOsWorkoutFigures(
    private val healthConnect: HealthConnectReads,
    /** The declared body profile, the one the watch's zones rest on: no Health Connect read. */
    private val profile: suspend () -> BodyProfile,
    private val zone: () -> ZoneId,
) {

    /** Null without a heart rate reading of the watch's in the session. [salt] is the watch's id salt. */
    suspend fun summarize(workout: WearOsWorkout, salt: String): WorkoutMetrics.Summary? {
        val beats = healthConnect.watchBeats(workout.start, workout.end.plus(RECOVERY_SPAN), salt)
        val end = workout.end.toEpochMilli()
        if (beats.none { it.epochMillis <= end }) return null
        val body = profile()
        val day = workout.start.atZone(zone()).toLocalDate()
        val observed = healthConnect.readMaxHeartRate(workout.end.minus(Duration.ofDays(observedMaxHeartRateLookbackDays)), workout.end)?.toInt()
        val basis = HeartRateZoneBasis.of(body, day, observed, healthConnect.latestNightRestingBpm(workout.start))
        val summary = WorkoutMetrics.summarize(WearOsWorkoutMetrics.linkWorkout(workout), beats, basis, body, day)
        val afterStop = beats.count { it.epochMillis > end }
        if (afterStop < WorkoutMetrics.MIN_RECOVERY_READINGS) return summary
        return summary.copy(recovery = WorkoutMetrics.recovery(beats, end, body, basis?.restingBpm, day))
    }

    private companion object {
        /** The watch measures a recovery for two minutes after the stop; its last mark may take a reading a few seconds past them. */
        val RECOVERY_SPAN: Duration = Duration.ofSeconds(130)
    }
}
