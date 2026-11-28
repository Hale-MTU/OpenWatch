package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import tech.mmarca.openwatch.core.presentation.isPermissionFailure
import tech.mmarca.openwatch.data.repository.HealthRecordStore
import tech.mmarca.openwatch.data.repository.RecordWrite
import tech.mmarca.openwatch.data.repository.insertWithOutcomes
import tech.mmarca.openwatch.devices.core.sync.HealthSeries
import tech.mmarca.openwatch.devices.core.sync.SkipReason
import tech.mmarca.openwatch.devices.core.sync.SkippedSeries
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.healthconnect.HealthConnectReads

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
    private val importRepository: HealthRecordStore,
    private val healthConnect: HealthConnectReads,
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
     * finished run is one record against the wearer's own baseline, once the
     * other nights give one; before, a night only joins the baseline. The
     * cursor moves to the end of the last night handled, so an unfinished run,
     * or a night a missing grant stopped, is pulled again next time. A time
     * cursor, not the watch's: a record is made from a whole run, which the
     * phone does not keep, and each run is counted into its night's median
     * once, whichever sync it comes with. False when no bonded watch matches.
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
        val permissions = granted()
        val canWrite = WearOsSkinTemperatureImport.writePermission in permissions
        val sleeps: List<ClosedRange<Instant>>? = if (SLEEP_READ in permissions) {
            // In the background without the grant to read other apps, Health Connect answers with
            // this app's sessions only and says nothing: the night would look sessionless.
            if (!healthConnect.readsOtherAppsDataNow()) {
                WearOsLog.log("skin: left for later, other apps' sleep is not readable in the background")
                return true
            }
            // Health Connect answers a range with the sessions inside it: a night's session begins hours
            // before a late run of it, so the read is widened both ways and the cut keeps what overlaps.
            when (val read = sleepSessions(finished.first().start.minus(SLEEP_LOOKAROUND), finished.last().end.plusSeconds(60).plus(SLEEP_LOOKAROUND))) {
                is SleepRead.Spans -> read.spans
                SleepRead.Refused -> null
                SleepRead.Failed -> return true
            }
        } else {
            null
        }
        val zone = zone()
        val now = clock()
        var medians = cursors.skinNightMedians(device.id)
        var handledUntil: Instant? = null
        var written = 0
        var withoutBaseline = 0
        var refused = 0
        nights@ for ((night, handled) in finished.groupBy { WearOsSkinTemperatureImport.nightOf(it, zone) }) {
            // A night that may still get its sleep session waits, and so does every later one: the cursor stays before it.
            val runs = WearOsSkinTemperatureImport.asleepRuns(night, handled, sleeps, zone, now) ?: break
            val baseline = WearOsSkinTemperatureImport.baseline(night, medians)
            // Too few other nights: this one only joins the baseline, which needs no grant.
            val kept = if (baseline == null) {
                withoutBaseline++
                runs
            } else {
                if (!canWrite) {
                    skipped += skinWithheld
                    WearOsLog.log("skin: not written, Health Connect permission missing")
                    break
                }
                val accepted = ArrayList<WearOsSkinTemperatureImport.Run>()
                for (run in runs) {
                    val record = WearOsSkinTemperatureImport.record(run, baseline, zone, version = clock().toEpochMilli(), salt = device.idSalt)
                    if (record == null) {
                        // Out of Health Connect's range, likely off the wrist: neither written nor remembered.
                        refused++
                        continue
                    }
                    written += try {
                        importRepository.insertWithOutcomes(listOf(record)).count { it == RecordWrite.WRITTEN }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (error !is SecurityException && !error.isPermissionFailure()) throw error
                        skipped += skinWithheld
                        break@nights
                    }
                    accepted += run
                }
                accepted
            }
            medians = WearOsSkinTemperatureImport.remember(medians, night, kept)
            // Past the awake minutes too: they were looked at, and are not pulled again.
            handledUntil = handled.last().end
        }
        if (withoutBaseline > 0) skipped += SkippedSeries(HealthSeries.SKIN_TEMPERATURE, SkipReason.NO_BASELINE)
        cursors.setSkinNightMedians(device.id, medians)
        // A night stopped by a missing grant is pulled again, so nothing of it is lost.
        handledUntil?.let { cursors.setSkinCursor(device.id, it) }
        WearOsLog.log("skin: $written run(s) written, $withoutBaseline night(s) without a baseline yet, $refused run(s) refused")
        return true
    }

    private sealed interface SleepRead {
        class Spans(val spans: List<ClosedRange<Instant>>) : SleepRead
        /** The grant is gone: the nights stand as they are, as without it. */
        data object Refused : SleepRead
        /** Anything else: the skin step waits for the next sync, the rest goes on. */
        data object Failed : SleepRead
    }

    /** The time asleep in the sleep sessions from any app inside `[from, to)` ([WearOsSkinTemperatureImport.asleepSpans]). */
    private suspend fun sleepSessions(from: Instant, to: Instant): SleepRead = try {
        val spans = ArrayList<ClosedRange<Instant>>()
        healthConnect.forEachSyncRecordPage(SleepSessionRecord::class, from, to) { page ->
            page.filterIsInstance<SleepSessionRecord>().forEach { spans += WearOsSkinTemperatureImport.asleepSpans(it) }
        }
        SleepRead.Spans(spans)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (error is SecurityException || error.isPermissionFailure()) {
            SleepRead.Refused
        } else {
            WearOsLog.log("skin: left for later, sleep sessions not read: ${error.javaClass.simpleName}")
            SleepRead.Failed
        }
    }

    private companion object {
        val SLEEP_READ: String = HealthPermission.getReadPermission(SleepSessionRecord::class)

        /** Longer than any night's session, as the step import's exercise lookaround is longer than any workout. */
        val SLEEP_LOOKAROUND: Duration = Duration.ofHours(30)
    }
}
