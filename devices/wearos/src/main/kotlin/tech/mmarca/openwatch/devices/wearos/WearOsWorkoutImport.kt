package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseLap
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import tech.mmarca.healthcalculations.insights.WorkoutEnergyResult
import tech.mmarca.healthcalculations.preferences.BodyProfile
import tech.mmarca.openwatch.devices.core.saltedRecordId
import tech.mmarca.openwatch.wearlink.WorkoutMetrics

/**
 * Maps a workout the watch recorded to Health Connect: an
 * `ExerciseSessionRecord` with its route and its pauses as pause segments,
 * and, for a workout that measured one, a `DistanceRecord` over the same
 * time; with its figures ([WearOsWorkoutMetrics]) its kilometres as laps and
 * a line of notes. As in OpenVitals, steps, climbed height and heart rate are not
 * written again: the watch's all-day series already carry them minute by
 * minute, and a second copy over the same minutes would count twice.
 *
 * Both ids are functions of the workout's start, so a pull that repeats
 * rewrites the session instead of adding a second one.
 *
 * Pure, so it is testable without a device.
 */
object WearOsWorkoutImport {

    const val KEY_PREFIX = "wearos_workout_"
    const val DISTANCE_PREFIX = "wearos_workout_distance_"
    const val ENERGY_PREFIX = "wearos_workout_energy_"

    /** Health Connect's own ceiling for one distance record. */
    private const val MAX_DISTANCE_METRES = 1_000_000.0

    val sessionPermission: String = HealthPermission.getWritePermission(ExerciseSessionRecord::class)
    val distancePermission: String = HealthPermission.getWritePermission(DistanceRecord::class)
    val energyPermission: String = HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class)
    const val routePermission: String = HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE

    /** The grants [workout]'s records need. */
    fun permissions(workout: WearOsWorkout): Set<String> = buildSet {
        add(sessionPermission)
        if (route(workout).isNotEmpty()) add(routePermission)
        if (distance(workout) != null) add(distancePermission)
    }

    fun records(
        workout: WearOsWorkout,
        zone: ZoneId,
        salt: String = "",
        laps: List<ExerciseLap> = emptyList(),
        notes: String? = null,
    ): List<Record> {
        val startOffset = zone.rules.getOffset(workout.start)
        val endOffset = zone.rules.getOffset(workout.end)
        val route = route(workout)
        val session = ExerciseSessionRecord(
            startTime = workout.start,
            startZoneOffset = startOffset,
            endTime = workout.end,
            endZoneOffset = endOffset,
            metadata = metadata(importKey(workout.start, salt)),
            exerciseType = workout.exerciseType,
            segments = workout.pauses
                .filter { it.start >= workout.start && it.endInclusive <= workout.end && it.start < it.endInclusive }
                .map { ExerciseSegment(it.start, it.endInclusive, ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE) },
            exerciseRoute = route.takeIf { it.isNotEmpty() }?.let(::ExerciseRoute),
            laps = laps,
            notes = notes,
        )
        val distance = distance(workout)?.let {
            DistanceRecord(
                startTime = workout.start,
                startZoneOffset = startOffset,
                endTime = workout.end,
                endZoneOffset = endOffset,
                distance = Length.meters(it),
                metadata = metadata(saltedRecordId("$DISTANCE_PREFIX${workout.start.toEpochMilli()}", salt)),
            )
        }
        return listOfNotNull(session, distance)
    }

    fun importKey(start: Instant, salt: String = ""): String = saltedRecordId("$KEY_PREFIX${start.toEpochMilli()}", salt)

    /**
     * The workout's active calories, estimated, as an `ActiveCaloriesBurnedRecord`
     * over the session: [WorkoutMetrics.energy], the one estimate the watch's
     * summary shows too, from the time-weighted mean of the watch's heart rate
     * [beats] over the session less its pauses (each pair of readings for the
     * time between them, at most five minutes), the walk or run's distance, or
     * the activity's Compendium MET, with the body [profile] and its age on
     * [day], the workout's own. The watch measures no calories itself; minutes
     * inside the session get no walking energy
     * ([WearOsStepsImport.outsideExerciseSessions]), so nothing counts twice.
     *
     * [WorkoutEnergyResult.MissingInputs] when the profile has no weight, so the
     * caller can say so; the id is a function of the start, so a repeat rewrites it.
     */
    fun activeCalories(
        workout: WearOsWorkout,
        zone: ZoneId,
        profile: BodyProfile,
        beats: List<WorkoutMetrics.Beat>,
        day: LocalDate,
        salt: String = "",
    ): Pair<ActiveCaloriesBurnedRecord?, WorkoutEnergyResult> {
        val link = WearOsWorkoutMetrics.linkWorkout(workout).copy(distanceMetres = distance(workout)?.toInt())
        val result = WorkoutMetrics.energy(link, beats, profile, day)
        val estimate = result as? WorkoutEnergyResult.Estimate ?: return null to result
        val record = ActiveCaloriesBurnedRecord(
            startTime = workout.start,
            startZoneOffset = zone.rules.getOffset(workout.start),
            endTime = workout.end,
            endZoneOffset = zone.rules.getOffset(workout.end),
            energy = Energy.kilocalories(estimate.activeKcal),
            metadata = metadata(saltedRecordId("$ENERGY_PREFIX${workout.start.toEpochMilli()}", salt)),
        )
        return record to result
    }

    // Every point must lie inside the session, or Health Connect refuses the route.
    private fun route(workout: WearOsWorkout): List<ExerciseRoute.Location> = workout.route
        .filter { !it.time.isBefore(workout.start) && it.time.isBefore(workout.end) }
        .map {
            ExerciseRoute.Location(
                time = it.time,
                latitude = it.latitude,
                longitude = it.longitude,
                horizontalAccuracy = it.accuracyMetres?.let(Length::meters),
                altitude = it.altitudeMetres?.let(Length::meters),
            )
        }

    private fun distance(workout: WearOsWorkout): Double? =
        workout.distanceMetres?.toDouble()?.takeIf { it > 0.0 && it <= MAX_DISTANCE_METRES }

    private fun metadata(id: String) = Metadata.autoRecorded(
        device = Device(type = Device.TYPE_WATCH),
        clientRecordId = id,
    )
}
