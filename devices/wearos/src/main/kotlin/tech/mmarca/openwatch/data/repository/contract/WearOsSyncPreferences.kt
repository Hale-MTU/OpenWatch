package tech.mmarca.openwatch.data.repository.contract

/**
 * What the Wear OS sync reads from the app's preferences: whether to write the watch's breathing
 * rate, off by default and not yet checked against a reference. The app's `OpenWatchPreferences`
 * implements it, as it implements `BodyProfilePreferences`.
 */
interface WearOsSyncPreferences {
    val writeRespiratoryRate: Boolean
}
