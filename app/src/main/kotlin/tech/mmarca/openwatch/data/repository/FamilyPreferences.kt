package tech.mmarca.openwatch.data.repository

import javax.inject.Inject
import tech.mmarca.openwatch.data.repository.contract.GarminActivityPreferences
import tech.mmarca.openwatch.data.repository.contract.WearOsSyncPreferences

/**
 * What the Garmin activity import keeps, in [OpenWatchPreferences] under its old keys. The
 * contract is `:devices:garmin`'s and the store is `:core:data`'s, which depends on no device
 * family, so the app joins the two.
 */
class GarminActivityPreferencesStore @Inject constructor(
    private val preferences: OpenWatchPreferences,
) : GarminActivityPreferences {
    override var lastActivityExerciseType: Int?
        get() = preferences.lastActivityExerciseType
        set(value) {
            preferences.lastActivityExerciseType = value
        }

    override val favoriteActivityExerciseType: Int?
        get() = preferences.favoriteActivityExerciseType
}

/** The preferences the Wear OS sync reads (`:devices:wearos`), from [OpenWatchPreferences]. */
class WearOsSyncPreferencesStore @Inject constructor(
    private val preferences: OpenWatchPreferences,
) : WearOsSyncPreferences {
    override val writeRespiratoryRate: Boolean
        get() = preferences.writeRespiratoryRate

    override val writeSleepBesideOthers: Boolean
        get() = preferences.writeSleepBesideOthers
}
