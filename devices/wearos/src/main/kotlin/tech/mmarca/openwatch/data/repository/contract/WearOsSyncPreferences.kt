package tech.mmarca.openwatch.data.repository.contract

/**
 * What the Wear OS sync reads from the app's preferences: whether to write the watch's breathing
 * rate, on by default though not yet checked against a reference, and whether to write the
 * watch's night beside a session another app wrote. The app's `OpenWatchPreferences` implements
 * it, as it implements `BodyProfilePreferences`.
 */
interface WearOsSyncPreferences {
    val writeRespiratoryRate: Boolean

    /** On: the night is written although another app holds a session over it, and Health Connect's priority decides. */
    val writeSleepBesideOthers: Boolean
}
