package tech.mmarca.openwatch.wear.workout

import androidx.annotation.StringRes
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wearlink.WorkoutSport

/** The sport's name on the watch: the workout screens, the ongoing notification and the watch face pill. */
@StringRes
internal fun sportName(sport: WorkoutSport): Int = when (sport) {
    WorkoutSport.WALKING -> R.string.workout_sport_walking
    WorkoutSport.RUNNING -> R.string.workout_sport_running
    WorkoutSport.HIKING -> R.string.workout_sport_hiking
    WorkoutSport.BIKING -> R.string.workout_sport_biking
    WorkoutSport.TREADMILL -> R.string.workout_sport_treadmill
    WorkoutSport.STAIR_CLIMBING -> R.string.workout_sport_stairs
    WorkoutSport.STAIR_CLIMBING_MACHINE -> R.string.workout_sport_stair_machine
    WorkoutSport.STATIONARY_BIKE -> R.string.workout_sport_stationary_bike
    WorkoutSport.ELLIPTICAL -> R.string.workout_sport_elliptical
    WorkoutSport.ROWING_MACHINE -> R.string.workout_sport_rowing
    WorkoutSport.STRENGTH_TRAINING -> R.string.workout_sport_strength
    WorkoutSport.HIIT -> R.string.workout_sport_hiit
    WorkoutSport.YOGA -> R.string.workout_sport_yoga
    WorkoutSport.PILATES -> R.string.workout_sport_pilates
    WorkoutSport.STRETCHING -> R.string.workout_sport_stretching
    WorkoutSport.DANCING -> R.string.workout_sport_dancing
    WorkoutSport.VOLLEYBALL -> R.string.workout_sport_volleyball
    WorkoutSport.OTHER_WORKOUT -> R.string.workout_sport_other
}
