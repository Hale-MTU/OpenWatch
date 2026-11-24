package tech.mmarca.openwatch.data.repository.contract

/**
 * What the Garmin activity import reads from and keeps in the app's preferences: the exercise
 * type a file that names none falls back to. The app's `OpenWatchPreferences` implements it,
 * as it implements `BodyProfilePreferences`.
 */
interface GarminActivityPreferences {

    /** The exercise type of the last activity imported; the fallback after [favoriteActivityExerciseType]. */
    var lastActivityExerciseType: Int?

    /** A preferred exercise type, asked before [lastActivityExerciseType]. */
    val favoriteActivityExerciseType: Int?
}
