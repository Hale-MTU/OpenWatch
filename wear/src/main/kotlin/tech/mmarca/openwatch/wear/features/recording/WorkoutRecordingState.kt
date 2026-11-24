package tech.mmarca.openwatch.wear.features.recording

import tech.mmarca.openwatch.wear.WatchSensors
import tech.mmarca.openwatch.wear.workout.WorkoutSnapshot
import tech.mmarca.openwatch.wearlink.WorkoutSport

/**
 * The recorder's workout as the workout screens draw it: the icon of the
 * nearest [ActivityType], the sport's own name, and the values it reports.
 */
internal fun WorkoutSnapshot.toRecordingState(sport: WorkoutSport, title: Int): RecordingUiState = RecordingUiState(
    activityType = activityTypeOf(sport),
    title = title,
    elapsedSeconds = activeSeconds,
    heartRateBpm = heartRateBpm,
    distanceMeters = distanceMetres,
    steps = steps,
    elevationGainMeters = ascentMetres,
    // No speed now (paused, or no fix for a while) stays none: the average is the summary's, not a live value.
    speedMetersPerSecond = speedMetresPerSecond,
    averageSpeedMetersPerSecond = averageSpeedMetresPerSecond,
    averageHeartRateBpm = averageHeartRateBpm,
    paused = paused,
    waitingForFix = waitingForFix,
)

/**
 * The fields the recorder fills for [sport] on a watch with [sensors]: the
 * heart rate with its sensor, the height climbed with the barometer, the
 * steps with the step counter, each only for a sport that has it. [gps] is
 * whether this workout records a route (`WorkoutUiState.gps`, which a watch
 * without a GPS receiver never does), not whether location is granted now:
 * a grant given during a workout recorded without one adds nothing. Until
 * the sensors are read ([sensors] null) every field the sport has is shown.
 * A workout's calories have no source without Health Services.
 */
internal fun workoutFieldsOf(sport: WorkoutSport, gps: Boolean, sensors: WatchSensors?): Set<WorkoutField> = buildSet {
    if (sensors?.heartRate != false) add(WorkoutField.HEART_RATE)
    if (sport.gps && gps) {
        add(WorkoutField.DISTANCE)
        add(if (sport.pace) WorkoutField.PACE else WorkoutField.SPEED)
    }
    if (sport.elevation && sensors?.barometer != false) add(WorkoutField.ELEVATION)
    if (sport.steps && sensors?.stepCounter != false) add(WorkoutField.STEPS)
}

internal fun activityTypeOf(sport: WorkoutSport): ActivityType = when (sport) {
    WorkoutSport.WALKING -> ActivityType.WALK
    WorkoutSport.RUNNING, WorkoutSport.TREADMILL -> ActivityType.RUN
    WorkoutSport.BIKING, WorkoutSport.STATIONARY_BIKE -> ActivityType.CYCLE
    WorkoutSport.HIKING -> ActivityType.HIKE
    else -> ActivityType.WORKOUT
}
