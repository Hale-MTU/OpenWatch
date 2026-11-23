package tech.mmarca.openwatch.data.repository.contract

import tech.mmarca.healthcalculations.preferences.BodyProfile

/**
 * The body the device estimates use (walking and workout energy, body composition): the
 * declared profile, with the weight and height Health Connect holds on top when their read is
 * granted. The app's `BodyProfileProvider` implements it; a device module takes this.
 */
interface BodyProfileRepository {

    /** What the user typed, with no Health Connect call. The whole answer in the background. */
    fun declared(): BodyProfile

    /** The declared profile with Health Connect's measurements. A read that fails throws, never passes for "none". */
    suspend fun resolve(): BodyProfile
}
