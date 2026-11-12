package tech.mmarca.openwatch.domain.insights

import java.time.LocalDate
import tech.mmarca.openwatch.domain.preferences.BodyProfile

/**
 * The two numbers the heart rate zones rest on. The body profile itself is
 * shared over OpenLink, so a maximum or resting rate entered in OpenVitals wins;
 * the rest is OpenWatch's fallback until OpenVitals shares its own zones.
 *
 * Maximum: the one the user entered (a measured maximum is what the ACSM
 * prefers over any formula); else the highest heart rate on record when it is
 * a real maximum ([isObservedMaxHeartRateTrustworthy], judged against the
 * entered resting rate, else the latest night's); else the age-predicted one
 * ([ageMaxHeartRate]: Tanaka, Gulati for women). The same functions as every
 * other maximum in the app, and as OpenVitals'.
 *
 * Resting: the one the user entered, and only that. The latest night's helps
 * judge the observed maximum, but it is not sent: the watch then places the
 * zones on the maximum alone.
 *
 * With a resting rate the watch places the zones on the heart rate reserve
 * (Karvonen), without one on the maximum, both at the ACSM's intensity classes
 * (Garber et al., Med Sci Sports Exerc 2011, doi:10.1249/MSS.0b013e318213fefb).
 */
data class HeartRateZoneBasis(val maxBpm: Int?, val restingBpm: Int?) {
    companion object {
        fun of(
            profile: BodyProfile,
            today: LocalDate = LocalDate.now(),
            observedMaxBpm: Int? = null,
            nightRestingBpm: Int? = null,
        ): HeartRateZoneBasis {
            val normalized = profile.normalized(today)
            val resting = normalized.restingHeartRateBpm ?: nightRestingBpm
            val observed = observedMaxBpm?.takeIf { max ->
                resting != null && isObservedMaxHeartRateTrustworthy(max, resting)
            }
            val predicted = normalized.ageYears(today)?.let { age -> ageMaxHeartRate(age, normalized.sex).toInt() }
            return HeartRateZoneBasis(normalized.maxHeartRateBpm ?: observed ?: predicted, normalized.restingHeartRateBpm)
        }
    }
}
