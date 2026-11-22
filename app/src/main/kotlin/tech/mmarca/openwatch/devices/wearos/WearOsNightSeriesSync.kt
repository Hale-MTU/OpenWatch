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
 * when the user switched it on) and the SpO2 spot measurements, one record per
 * value by the watch's sequence cursor ([WearOsSeriesSync]), and the skin
 * temperature, one record per night run. Each is optional: a missing grant
 * leaves its rows on the watch, marks the series as unwritten for the sync
 * card, and the sync goes on.
 */
internal class WearOsNightSeriesSync(
    private val importRepository: ImportedRecordRepository,
    private val healthConnect: HealthConnectGateway,
    private val cursors: WearOsSyncCursorStore,
    private val clock: () -> Instant,
    private val zone: () -> ZoneId,
    private val writeRespiratoryRate: () -> Boolean,
) {

    /** The series a missing grant kept out, each with its grant. */
    class Outcome(val skipped: Set<SkippedSeries>)

    private val granted: suspend () -> Set<String> = { healthConnect.grantedPermissions() }

    private val skinWithheld = SkippedSeries(
        HealthSeries.SKIN_TEMPERATURE,
        SkipReason.PERMISSION,
        setOf(WearOsSkinTemperatureImport.writePermission),
    )

    private val perValue = WearOsSeriesSync(importRepository, granted, cursors, zone)

    /** Null when no bonded watch matches. A failure other than a missing grant throws. */
    suspend fun sync(device: BleSensorDevice, reader: WearOsSeriesReader): Outcome? {
        val skipped = mutableSetOf<SkippedSeries>()

        suspend fun <T : Any> write(series: WearSeries<T>, timeCursor: WearOsSeriesSync.TimeCursor): Boolean {
            val outcome = perValue.sync(device, series, timeCursor, reader) ?: return false
            val writes = checkNotNull(series.writes)
            if (outcome.withheld) {
                skipped += SkippedSeries(writes.healthSeries, SkipReason.PERMISSION, setOf(writes.permission))
                WearOsLog.log("${writes.label}: not written, Health Connect permission missing")
            }
            WearOsLog.log("${writes.label}: ${outcome.written} written")
            return true
        }

        val id = device.id
        if (!write(WearOsSeries.HRV, WearOsSeriesSync.TimeCursor({ cursors.hrvCursor(id) }, { cursors.setHrvCursor(id, it) }))) return null
        if (writeRespiratoryRate()) {
            val cursor = WearOsSeriesSync.TimeCursor({ cursors.respiratoryCursor(id) }, { cursors.setRespiratoryCursor(id, it) })
            if (!write(WearOsSeries.RESPIRATORY_RATE, cursor)) return null
        }
        if (!write(WearOsSeries.SPO2, WearOsSeriesSync.TimeCursor({ cursors.spo2Cursor(id) }, { cursors.setSpo2Cursor(id, it) }))) return null
        if (!syncSkin(device, reader, skipped)) return null
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
        reader: WearOsSeriesReader,
        skipped: MutableSet<SkippedSeries>,
    ): Boolean {
        if (!healthConnect.isSkinTemperatureAvailable()) return true
        val minutes = reader.newerThan(device, WearOsSeries.SKIN_TEMPERATURE, cursors.skinCursor(device.id)) ?: return false
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
}
