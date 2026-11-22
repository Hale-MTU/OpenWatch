package tech.mmarca.openwatch.devices.wearos

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import tech.mmarca.openwatch.core.presentation.isPermissionFailure
import tech.mmarca.openwatch.data.repository.ImportedRecordRepository
import tech.mmarca.openwatch.data.repository.RecordWrite
import tech.mmarca.openwatch.data.repository.insertWithOutcomes
import tech.mmarca.openwatch.devices.core.sync.HealthSeries
import tech.mmarca.openwatch.devices.core.sync.SkipReason
import tech.mmarca.openwatch.devices.core.sync.SkippedSeries
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.healthconnect.HealthConnectGateway

/**
 * The series the watch records at night beside sleep, each written to Health
 * Connect when its grant is in: the HRV windows, their breathing rate (only
 * when the user switched it on), the SpO2 spot measurements and the skin
 * temperature. Each is optional: a missing grant leaves its rows on the
 * watch, marks the series as unwritten for the sync card, and the sync goes on.
 */
internal class WearOsNightSeriesSync(
    private val importRepository: ImportedRecordRepository,
    private val healthConnect: HealthConnectGateway,
    private val cursors: WearOsSyncCursorStore,
    private val nodePort: WearOsNodePort,
    private val clock: () -> Instant,
    private val zone: () -> ZoneId,
    private val writeRespiratoryRate: () -> Boolean,
) {

    /** A pull again after one link failure; the sync service's own policy. */
    interface Retry {
        suspend fun <T> run(pull: suspend () -> T): T
    }

    /** The series a missing grant kept out, each with its grant. */
    class Outcome(val skipped: Set<SkippedSeries>)

    private val granted: suspend () -> Set<String> = { healthConnect.grantedPermissions() }

    private val skinWithheld = SkippedSeries(
        HealthSeries.SKIN_TEMPERATURE,
        SkipReason.PERMISSION,
        setOf(WearOsSkinTemperatureImport.writePermission),
    )

    private val hrv = WearOsWindowSync<WearOsHrvWindow>(
        importRepository = importRepository,
        granted = granted,
        writePermission = WearOsHrvImport.writePermission,
        cursor = cursors::hrvCursor,
        setCursor = cursors::setHrvCursor,
        startOf = { it.start },
        record = { window, salt -> WearOsHrvImport.record(window, zone(), salt) },
        label = "hrv",
    )

    private val respiratory = WearOsWindowSync<WearOsRespiratoryWindow>(
        importRepository = importRepository,
        granted = granted,
        writePermission = WearOsRespiratoryImport.writePermission,
        cursor = cursors::respiratoryCursor,
        setCursor = cursors::setRespiratoryCursor,
        startOf = { it.start },
        record = { window, salt -> WearOsRespiratoryImport.record(window, zone(), salt) },
        label = "breathing",
    )

    private val spo2 = WearOsWindowSync<WearOsSpo2Measurement>(
        importRepository = importRepository,
        granted = granted,
        writePermission = WearOsSpo2Import.writePermission,
        cursor = cursors::spo2Cursor,
        setCursor = cursors::setSpo2Cursor,
        startOf = { it.time },
        record = { measurement, salt -> WearOsSpo2Import.record(measurement, zone(), salt) },
        label = "spo2",
    )

    /** Null when no bonded watch matches. A failure other than a missing grant throws. */
    suspend fun sync(device: BleSensorDevice, retry: Retry): Outcome? {
        val skipped = mutableSetOf<SkippedSeries>()
        fun note(outcome: WearOsWindowSync.Outcome, series: HealthSeries, permission: String, label: String) {
            if (outcome.withheld) {
                skipped += SkippedSeries(series, SkipReason.PERMISSION, setOf(permission))
                WearOsLog.log("$label: not written, Health Connect permission missing")
            }
            WearOsLog.log("$label: ${outcome.written} written")
        }

        val hrvOutcome = hrv.sync(device.id, device.idSalt) { since ->
            retry.run { nodePort.pullHrv(device.address, device.bluetoothName, since) }?.let { it.windows to it.hasMore }
        } ?: return null
        note(hrvOutcome, HealthSeries.HEART_RATE_VARIABILITY, WearOsHrvImport.writePermission, "hrv")

        if (writeRespiratoryRate()) {
            val outcome = respiratory.sync(device.id, device.idSalt) { since ->
                retry.run { nodePort.pullRespiratory(device.address, device.bluetoothName, since) }?.let { it.windows to it.hasMore }
            } ?: return null
            note(outcome, HealthSeries.RESPIRATORY_RATE, WearOsRespiratoryImport.writePermission, "breathing")
        }

        val spo2Outcome = spo2.sync(device.id, device.idSalt) { since ->
            retry.run { nodePort.pullSpo2(device.address, device.bluetoothName, since) }?.let { it.measurements to it.hasMore }
        } ?: return null
        note(spo2Outcome, HealthSeries.OXYGEN_SATURATION, WearOsSpo2Import.writePermission, "spo2")

        if (!syncSkin(device, retry, skipped)) return null
        return Outcome(skipped)
    }

    /**
     * The skin temperature minutes since the cursor, cut into night runs; each
     * finished run is one record against the wearer's own baseline. The
     * cursor moves to the end of the last finished run, so an unfinished one
     * is pulled again next time. False when no bonded watch matches.
     */
    private suspend fun syncSkin(
        device: BleSensorDevice,
        retry: Retry,
        skipped: MutableSet<SkippedSeries>,
    ): Boolean {
        if (!healthConnect.isSkinTemperatureAvailable()) return true
        val minutes = ArrayList<WearOsSkinMinute>()
        var since = cursors.skinCursor(device.id)
        var pages = 0
        while (true) {
            val page = retry.run { nodePort.pullSkin(device.address, device.bluetoothName, since) } ?: return false
            minutes += page.minutes
            val newest = page.minutes.maxOfOrNull { it.time }
            if (!page.hasMore || newest == null || ++pages >= MAX_PAGES) break
            since = newest
        }
        val finished = WearOsSkinTemperatureImport.finished(WearOsSkinTemperatureImport.runs(minutes), clock())
        if (finished.isEmpty()) return true
        if (WearOsSkinTemperatureImport.writePermission !in granted()) {
            skipped += skinWithheld
            WearOsLog.log("skin: not written, Health Connect permission missing")
            return true
        }
        var medians = cursors.skinNightMedians(device.id)
        var written = 0
        for (run in finished) {
            val record = WearOsSkinTemperatureImport.record(
                run,
                WearOsSkinTemperatureImport.baseline(run, medians),
                zone(),
                version = clock().toEpochMilli(),
                salt = device.idSalt,
            )
            if (record != null) {
                written += try {
                    importRepository.insertWithOutcomes(listOf(record)).count { it == RecordWrite.WRITTEN }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (error !is SecurityException && !error.isPermissionFailure()) throw error
                    skipped += skinWithheld
                    return true
                }
            }
            medians = (medians + run.median).takeLast(WearOsSkinTemperatureImport.MAX_BASELINE_NIGHTS)
        }
        cursors.setSkinNightMedians(device.id, medians)
        cursors.setSkinCursor(device.id, finished.last().end)
        WearOsLog.log("skin: $written night(s) written")
        return true
    }

    private companion object {
        const val MAX_PAGES = 1000
    }
}
