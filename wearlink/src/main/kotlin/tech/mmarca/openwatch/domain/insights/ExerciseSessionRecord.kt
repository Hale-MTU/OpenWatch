package tech.mmarca.openwatch.domain.insights

/**
 * The Health Connect exercise types the workout energy estimate reads, under the
 * names of `androidx.health.connect.client.records.ExerciseSessionRecord`. This
 * module is plain Kotlin, so it cannot see that class, and the watch needs the
 * estimate too. With the same name in this package, [CompendiumMet] and
 * [estimateWorkoutEnergy] read the same as OpenVitals' copies without the
 * androidx import. A file that imports the androidx class still gets that one.
 * The values are Health Connect's (connect-client 1.2.0-alpha06), and
 * ExerciseTypeMirrorTest in :domain checks each against it.
 */
object ExerciseSessionRecord {
    const val EXERCISE_TYPE_BADMINTON = 2
    const val EXERCISE_TYPE_BASEBALL = 4
    const val EXERCISE_TYPE_BASKETBALL = 5
    const val EXERCISE_TYPE_BIKING = 8
    const val EXERCISE_TYPE_BIKING_STATIONARY = 9
    const val EXERCISE_TYPE_BOOT_CAMP = 10
    const val EXERCISE_TYPE_BOXING = 11
    const val EXERCISE_TYPE_CALISTHENICS = 13
    const val EXERCISE_TYPE_CRICKET = 14
    const val EXERCISE_TYPE_DANCING = 16
    const val EXERCISE_TYPE_ELLIPTICAL = 25
    const val EXERCISE_TYPE_EXERCISE_CLASS = 26
    const val EXERCISE_TYPE_FENCING = 27
    const val EXERCISE_TYPE_FOOTBALL_AMERICAN = 28
    const val EXERCISE_TYPE_FRISBEE_DISC = 31
    const val EXERCISE_TYPE_GOLF = 32
    const val EXERCISE_TYPE_GYMNASTICS = 34
    const val EXERCISE_TYPE_HANDBALL = 35
    const val EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING = 36
    const val EXERCISE_TYPE_HIKING = 37
    const val EXERCISE_TYPE_ICE_HOCKEY = 38
    const val EXERCISE_TYPE_ICE_SKATING = 39
    const val EXERCISE_TYPE_MARTIAL_ARTS = 44
    const val EXERCISE_TYPE_PADDLING = 46
    const val EXERCISE_TYPE_PARAGLIDING = 47
    const val EXERCISE_TYPE_PILATES = 48
    const val EXERCISE_TYPE_RACQUETBALL = 50
    const val EXERCISE_TYPE_ROCK_CLIMBING = 51
    const val EXERCISE_TYPE_ROLLER_HOCKEY = 52
    const val EXERCISE_TYPE_ROWING = 53
    const val EXERCISE_TYPE_ROWING_MACHINE = 54
    const val EXERCISE_TYPE_RUGBY = 55
    const val EXERCISE_TYPE_RUNNING = 56
    const val EXERCISE_TYPE_RUNNING_TREADMILL = 57
    const val EXERCISE_TYPE_SAILING = 58
    const val EXERCISE_TYPE_SCUBA_DIVING = 59
    const val EXERCISE_TYPE_SKATING = 60
    const val EXERCISE_TYPE_SKIING = 61
    const val EXERCISE_TYPE_SNOWBOARDING = 62
    const val EXERCISE_TYPE_SNOWSHOEING = 63
    const val EXERCISE_TYPE_SOCCER = 64
    const val EXERCISE_TYPE_SOFTBALL = 65
    const val EXERCISE_TYPE_SQUASH = 66
    const val EXERCISE_TYPE_STAIR_CLIMBING = 68
    const val EXERCISE_TYPE_STAIR_CLIMBING_MACHINE = 69
    const val EXERCISE_TYPE_STRENGTH_TRAINING = 70
    const val EXERCISE_TYPE_STRETCHING = 71
    const val EXERCISE_TYPE_SURFING = 72
    const val EXERCISE_TYPE_SWIMMING_OPEN_WATER = 73
    const val EXERCISE_TYPE_SWIMMING_POOL = 74
    const val EXERCISE_TYPE_TABLE_TENNIS = 75
    const val EXERCISE_TYPE_TENNIS = 76
    const val EXERCISE_TYPE_VOLLEYBALL = 78
    const val EXERCISE_TYPE_WALKING = 79
    const val EXERCISE_TYPE_WATER_POLO = 80
    const val EXERCISE_TYPE_WEIGHTLIFTING = 81
    const val EXERCISE_TYPE_YOGA = 83
}
