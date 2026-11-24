package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.records.RestingHeartRateRecord
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import tech.mmarca.healthcalculations.insights.HeartRateZoneBasis
import tech.mmarca.healthcalculations.insights.observedMaxHeartRateLookbackDays
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
 * basis both are `0`. An extra: a failed read or an old watch app skips it,
 * and it never fails the sync.
 */
internal class WearOsZoneSync(
    private val healthConnect: HealthConnectReads,
    private val nodePort: WearOsNodePort,
    private val profile: suspend () -> BodyProfile,
    private val clock: () -> Instant,
    private val zone: () -> ZoneId,
) {

    suspend fun sync(device: BleSensorDevice, retry: WearOsLinkRetry) {
        val basis = try {
            val now = clock()
            val observed = healthConnect.readMaxHeartRate(now.minus(Duration.ofDays(observedMaxHeartRateLookbackDays)), now)?.toInt()
            HeartRateZoneBasis.of(profile(), now.atZone(zone()).toLocalDate(), observed, healthConnect.latestNightRestingBpm(now))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            WearOsLog.log("zones: not read: ${error.javaClass.simpleName}")
            return
        }
        for ((key, bpm) in listOf(Preferences.HEART_RATE_MAX to basis?.maxBpm, Preferences.HEART_RATE_REST to basis?.restingBpm)) {
            retry.run { nodePort.setWatchSetting(device.address, device.bluetoothName, key, (bpm ?: 0).toString()) }
        }
        WearOsLog.log("zones: sent")
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
