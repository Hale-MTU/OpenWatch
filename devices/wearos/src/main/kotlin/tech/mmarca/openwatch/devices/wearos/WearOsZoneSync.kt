package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.records.RestingHeartRateRecord
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import tech.mmarca.healthcalculations.insights.HeartRateZoneBasis
import tech.mmarca.healthcalculations.insights.observedMaxHeartRateLookbackDays
import tech.mmarca.healthcalculations.preferences.BiologicalSex
import tech.mmarca.healthcalculations.preferences.BodyProfile
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.healthconnect.HealthConnectReads
import tech.mmarca.openwatch.wearlink.WearLinkPreferences as Preferences

/**
 * Hands the watch what its heart rate zones rest on ([HeartRateZoneBasis],
 * from health-calculations, the same as OpenVitals'): the maximum and the
 * resting heart rate, as two watch settings, after each sync. From the body
 * profile (shared with OpenVitals over OpenLink), the highest heart rate
 * Health Connect holds for the year before and the latest night's resting
 * rate, which only decides whether that maximum is believable. Without a
 * basis both are `0`. Beside them the body profile a workout's figures need
 * on the watch ([bodyProfile]: the weight, Health Connect's latest else the
 * profile's, the height, the birth year and the sex), as four more settings.
 * Extras: a failed read skips only what it was for (a weight that cannot be
 * read still sends the zones), an old watch app skips both, and neither
 * ever fails the sync.
 */
internal class WearOsZoneSync(
    private val healthConnect: HealthConnectReads,
    private val nodePort: WearOsNodePort,
    /** The declared profile the zones rest on: no Health Connect read. */
    private val profile: suspend () -> BodyProfile,
    /** The profile for the watch's workout figures, its weight from Health Connect where there is one. Strict: a failed read throws. */
    private val bodyProfile: suspend () -> BodyProfile,
    private val clock: () -> Instant,
    private val zone: () -> ZoneId,
) {

    suspend fun sync(device: BleSensorDevice, retry: WearOsLinkRetry) {
        val zones = read("zones") {
            val now = clock()
            val observed = healthConnect.readMaxHeartRate(now.minus(Duration.ofDays(observedMaxHeartRateLookbackDays)), now)?.toInt()
            val basis = HeartRateZoneBasis.of(profile(), now.atZone(zone()).toLocalDate(), observed, healthConnect.latestNightRestingBpm(now))
            listOf(
                Preferences.HEART_RATE_MAX to (basis?.maxBpm ?: 0).toString(),
                Preferences.HEART_RATE_REST to (basis?.restingBpm ?: 0).toString(),
            )
        }
        val body = read("profile") { profileSettings(bodyProfile()) }
        for ((key, value) in zones.orEmpty() + body.orEmpty()) {
            retry.run { nodePort.setWatchSetting(device.address, device.bluetoothName, key, value) }
        }
        if (zones != null) WearOsLog.log("zones: sent")
        if (body != null) WearOsLog.log("profile: sent")
    }

    /** The settings [settings] makes; null, logged under [what], when a read for them fails. */
    private suspend fun read(what: String, settings: suspend () -> List<Pair<String, String>>): List<Pair<String, String>>? =
        try {
            settings()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            WearOsLog.log("$what: not read: ${error.javaClass.simpleName}")
            null
        }

    companion object {
        /**
         * The watch's settings for [body]: the weight in 100 g and the height in whole cm, each
         * `0` without one or out of the range the watch takes, the birth year, and the sex.
         */
        fun profileSettings(body: BodyProfile): List<Pair<String, String>> = listOf(
            Preferences.BODY_WEIGHT to (body.weightKg?.let { Math.round(it * HECTOGRAMS_PER_KG).toInt() }?.takeIf { it in WEIGHT_HECTOGRAMS } ?: 0).toString(),
            Preferences.BODY_HEIGHT to (body.heightCm?.let { Math.round(it).toInt() }?.takeIf { it in HEIGHT_CM } ?: 0).toString(),
            Preferences.BIRTH_YEAR to (body.birthYear?.takeIf { it in BIRTH_YEARS } ?: 0).toString(),
            Preferences.SEX to when (body.sex) {
                BiologicalSex.FEMALE -> Preferences.FEMALE
                BiologicalSex.MALE -> Preferences.MALE
                null -> Preferences.OFF
            },
        )

        private const val HECTOGRAMS_PER_KG = 10.0

        // What the watch takes (WearPreferenceCodec): a value outside is sent as none rather than refused.
        private val WEIGHT_HECTOGRAMS = 200..4000
        private val HEIGHT_CM = 80..260
        private val BIRTH_YEARS = 1900..2100
    }
}

/**
 * The latest night's resting heart rate of the week before [before], from any app: what
 * [HeartRateZoneBasis] judges an observed maximum by, for the zones and a workout's figures.
 */
internal suspend fun HealthConnectReads.latestNightRestingBpm(before: Instant): Int? {
    var latest: RestingHeartRateRecord? = null
    forEachSyncRecordPage(RestingHeartRateRecord::class, before.minus(RESTING_LOOKBACK), before) { page ->
        for (record in page.filterIsInstance<RestingHeartRateRecord>()) if (latest == null || record.time > latest!!.time) latest = record
    }
    return latest?.beatsPerMinute?.toInt()
}

private val RESTING_LOOKBACK: Duration = Duration.ofDays(7)
