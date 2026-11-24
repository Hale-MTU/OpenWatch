package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.healthconnect.HealthConnectReads

/**
 * Hands the watch the phone's figures for the last [DAYS] days after each
 * sync (`PUT_DAY`, [WearOsDaySummaries]): the night, the resting heart rate,
 * the active energy and the distance, which the watch shows on its own
 * tiles. From any app in Health Connect, so the figures are the day's, not
 * only OpenWatch's own: a sync in the background without the right to read
 * other apps' data ([HealthConnectReads.readsOtherAppsDataNow]) sends none,
 * rather than figures that leave out every other app. A figure whose read
 * grant is missing stays off the watch. An extra, like [WearOsZoneSync]: a
 * failed read or an old watch app skips it, and it never fails the sync.
 */
internal class WearOsDaySync(
    private val healthConnect: HealthConnectReads,
    private val nodePort: WearOsNodePort,
    private val clock: () -> Instant,
    private val zone: () -> ZoneId,
) {

    suspend fun sync(device: BleSensorDevice, retry: WearOsLinkRetry) {
        val summaries = try {
            if (!healthConnect.readsOtherAppsDataNow()) {
                WearOsLog.log("days: not sent, other apps' data is not readable in the background")
                return
            }
            val zone = zone()
            val days = WearOsDaySummaries.lastDays(clock(), zone, DAYS)
            // A night that ended on the first day began the evening before.
            val from = days.first().minusDays(1).atStartOfDay(zone).toInstant()
            val to = days.last().plusDays(1).atStartOfDay(zone).toInstant()
            val granted = healthConnect.grantedPermissions()
            val sleep = if (SLEEP_READ in granted) read<SleepSessionRecord>(from, to) else emptyList()
            val resting = if (RESTING_READ in granted) read<RestingHeartRateRecord>(from, to) else emptyList()
            WearOsDaySummaries.summaries(days, zone, sleep, resting, healthConnect.readDayTotals(days.first(), days.last()))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            WearOsLog.log("days: not read: ${error.javaClass.simpleName}")
            return
        }
        val sent = try {
            retry.run { nodePort.pushDays(device.address, device.bluetoothName, summaries) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            WearOsLog.log("days: not sent: ${error.javaClass.simpleName}")
            return
        }
        WearOsLog.log("days: ${if (sent) summaries.size else 0} sent")
    }

    private suspend inline fun <reified T : Record> read(from: Instant, to: Instant): List<T> {
        val records = ArrayList<T>()
        healthConnect.forEachSyncRecordPage(T::class, from, to) { page -> page.filterIsInstanceTo(records) }
        return records
    }

    private companion object {
        /** The week the watch's screens show. */
        const val DAYS = 7
        val SLEEP_READ = HealthPermission.getReadPermission(SleepSessionRecord::class)
        val RESTING_READ = HealthPermission.getReadPermission(RestingHeartRateRecord::class)
    }
}
