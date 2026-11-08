package tech.mmarca.openwatch.link

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.mmarca.openlink.OpenLinkProfile
import tech.mmarca.openlink.OpenLinkSex
import tech.mmarca.openwatch.data.repository.contract.BodyProfilePreferences
import tech.mmarca.openwatch.domain.preferences.BiologicalSex
import tech.mmarca.openwatch.domain.preferences.BodyProfile

/**
 * The body profile across the link, last writer wins. OpenWatch keeps the time of its last
 * change beside the profile; a pushed profile older than that is ignored, a newer one
 * replaces it. A change made here is stamped when it shows on the profile flow.
 */
@Singleton
class LinkProfileSync @Inject constructor(
    @ApplicationContext context: Context,
    private val preferences: BodyProfilePreferences,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _updatedAtMillis = MutableStateFlow(prefs.getLong(KEY_UPDATED_AT, 0L))

    /**
     * When the profile last changed on either side, as both sides agree on it; 0 or 1 for
     * one that predates the link.
     */
    val updatedAtMillis: StateFlow<Long> = _updatedAtMillis.asStateFlow()

    fun current(): OpenLinkProfile =
        preferences.bodyProfile().toLink(prefs.getLong(KEY_UPDATED_AT, 0L))

    /** Takes [profile] if it is newer than what is here. Returns whether it did. */
    @Synchronized
    fun accept(profile: OpenLinkProfile): Boolean {
        if (profile.updatedAtMillis <= prefs.getLong(KEY_UPDATED_AT, 0L)) return false
        val body = profile.toBody()
        preferences.setBodyProfile(body)
        val stored = preferences.bodyProfile()
        prefs.edit {
            putLong(KEY_UPDATED_AT, profile.updatedAtMillis)
            putString(KEY_SIGNATURE, stored.signature())
        }
        _updatedAtMillis.value = profile.updatedAtMillis
        return true
    }

    /**
     * Called for each value of the profile flow. A profile that differs from the last
     * stamped one is a change made here: stamp it now. Returns the profile to publish, or
     * null for one this side already knows (its own echo of an accepted push).
     */
    @Synchronized
    fun onLocalProfile(profile: BodyProfile, now: Long = System.currentTimeMillis()): OpenLinkProfile? {
        val signature = profile.signature()
        val known = prefs.getString(KEY_SIGNATURE, null)
        if (signature == known) return null
        // The first profile ever seen predates the link: stamped 1 (0 if empty), so a real
        // profile on either side beats an empty one and any later edit beats both.
        val stamp = when {
            known != null -> now
            profile.isUnset() -> 0L
            else -> 1L
        }
        prefs.edit {
            putLong(KEY_UPDATED_AT, stamp)
            putString(KEY_SIGNATURE, signature)
        }
        _updatedAtMillis.value = stamp
        return profile.toLink(stamp).takeIf { stamp > 0 }
    }

    private companion object {
        const val PREFS = "openlink_profile"
        const val KEY_UPDATED_AT = "updated_at"
        const val KEY_SIGNATURE = "signature"
    }
}

internal fun BodyProfile.toLink(updatedAtMillis: Long): OpenLinkProfile = OpenLinkProfile(
    birthYear = birthYear,
    sex = when (sex) {
        BiologicalSex.FEMALE -> OpenLinkSex.FEMALE
        BiologicalSex.MALE -> OpenLinkSex.MALE
        null -> null
    },
    heightCm = heightCm,
    weightKg = weightKg,
    restingHeartRateBpm = restingHeartRateBpm,
    maxHeartRateBpm = maxHeartRateBpm,
    updatedAtMillis = updatedAtMillis,
)

internal fun BodyProfile.isUnset(): Boolean =
    birthYear == null && weightKg == null && heightCm == null && restingHeartRateBpm == null &&
        maxHeartRateBpm == null && sex == null

internal fun OpenLinkProfile.toBody(): BodyProfile = BodyProfile(
    birthYear = birthYear,
    weightKg = weightKg,
    heightCm = heightCm,
    restingHeartRateBpm = restingHeartRateBpm,
    maxHeartRateBpm = maxHeartRateBpm,
    sex = when (sex) {
        OpenLinkSex.FEMALE -> BiologicalSex.FEMALE
        OpenLinkSex.MALE -> BiologicalSex.MALE
        null -> null
    },
)
