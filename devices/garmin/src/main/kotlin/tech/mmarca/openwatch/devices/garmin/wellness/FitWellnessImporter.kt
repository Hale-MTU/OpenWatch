package tech.mmarca.openwatch.devices.garmin.wellness

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.CancellationException
import tech.mmarca.healthcalculations.insights.SleepStageEstimator
import tech.mmarca.healthcalculations.model.GarminSleepMinute
import tech.mmarca.openwatch.data.repository.HealthRecordStore
import tech.mmarca.openwatch.data.repository.contract.GarminSleepMinuteRepository
import tech.mmarca.openwatch.data.repository.contract.GarminWellnessRepository
import tech.mmarca.openwatch.data.repository.insertSkippingPresent
import tech.mmarca.openwatch.data.repository.partitionByWriteGrant
import tech.mmarca.openwatch.devices.garmin.GarminCounterWatermarkStore
import tech.mmarca.openwatch.devices.garmin.GarminDownloadedFile
import tech.mmarca.openwatch.devices.garmin.GarminLog
import tech.mmarca.openwatch.domain.model.GarminWellnessMetric
import tech.mmarca.openwatch.domain.model.GarminWellnessSample
import tech.mmarca.openwatch.healthconnect.HealthConnectReads

/**
 * Runs the wellness import for one sync's FIT files: Health Connect records
 * through the shared insert pipeline, the cumulative counters accumulated
 * across every file and differenced once against the watermarks, and the
 * watch-only metrics into [GarminWellnessRepository]. Activity files are
 * left to the activity importer.
 *
 * A record type whose Health Connect write is not granted is left out and its permission
 * returned, so one withheld grant costs that type, not the whole import: a failed import
 * keeps its files pending, and every later sync would stop at them.
 *
 * Throws when the write path is unavailable; a single bad file is skipped.
 */
@Singleton
class FitWellnessImporter(
    private val importRepository: HealthRecordStore,
    private val wellnessRepository: GarminWellnessRepository,
    private val watermarkStore: GarminCounterWatermarkStore,
    private val sleepMinuteRepository: GarminSleepMinuteRepository,
    private val sleepEstimation: GarminSleepEstimationWriter,
    private val grantedPermissions: suspend () -> Set<String>,
) {
    @Inject
    constructor(
        importRepository: HealthRecordStore,
        wellnessRepository: GarminWellnessRepository,
        watermarkStore: GarminCounterWatermarkStore,
        sleepMinuteRepository: GarminSleepMinuteRepository,
        sleepEstimation: GarminSleepEstimationWriter,
        healthConnect: HealthConnectReads,
    ) : this(
        importRepository,
        wellnessRepository,
        watermarkStore,
        sleepMinuteRepository,
        sleepEstimation,
        healthConnect::grantedPermissions,
    )

    /**
     * Imports [files] from the watch with id salt [salt]; returns the write permissions whose
     * records were left out. The salt keys its records' ids, its counters' marks and its rows.
     */
    suspend fun import(files: List<GarminDownloadedFile>, salt: String = ""): Set<String> {
        if (files.isEmpty()) return emptySet()
        val granted = grantedPermissions()
        val withheld = sortedSetOf<String>()

        val records = mutableListOf<Record>()
        var counters = FitMonitoringCounters()
        val watchOnly = mutableListOf<GarminWellnessSample>()
        val sleepMinutes = mutableListOf<GarminSleepMinute>()
        val nightsWithRealStages = mutableSetOf<LocalDate>()

        for (file in files) {
            val wellness = try {
                parseGarminWellness(file.bytes, fileName = file.entry.type.label)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // A rejected file must not sink the files beside it.
                GarminLog.log(
                    "[GARMIN-IMPORT] undecodable ${file.entry.type.label} " +
                        "index=${file.entry.fileIndex}: $error",
                )
                continue
            }

            wellness.sleep?.let { sleep ->
                records += fitSleepImportRecords(sleep, salt)
                SleepStageEstimator.nightDateOf(sleep.start, ZoneId.systemDefault().rules.getOffset(sleep.start))
                    ?.let(nightsWithRealStages::add)
            }
            sleepMinutes += wellness.sleepMinutes.map { it.toGarminSleepMinute() }
            wellness.hrv?.let { records += fitHrvImportRecords(it, salt) }
            wellness.monitoring?.let { records += fitMonitoringImportRecords(it, salt) }
            wellness.metrics?.let { records += fitMetricsImportRecords(it, salt) }
            records += fitNapImportRecords(wellness.naps, salt)
            wellness.healthSnapshot?.let { records += fitHealthSnapshotImportRecords(it, salt) }
            records += fitExtrasImportRecords(wellness.extras, salt)
            wellness.weights.forEach { records += fitWeightImportRecords(it, salt) }

            // Counters are mapped once at the end: an interval needs the reading before it.
            wellness.monitoring?.let { counters = counters.merge(fitMonitoringCounters(it)) }

            watchOnly += watchOnlySamples(wellness)
            watchOnly += fitExtrasWatchOnlySamples(
                wellness.extras,
                nightStart = wellness.sleep?.start ?: wellness.sleepMinutes.firstOrNull()?.time,
            )
        }

        withheld += writeRecords(records, granted)
        withheld += writeCounters(counters, granted, salt)

        // After the Health Connect writes, like the Flutter build. A failure still fails the sync.
        wellnessRepository.upsert(watchOnly, salt)
        if (watchOnly.isNotEmpty()) {
            GarminLog.log("[GARMIN-IMPORT] stored ${watchOnly.size} watch-only samples")
        }

        // The minutes are kept either way: once the grant comes, a later sync estimates the nights.
        if (sleepMinutes.isNotEmpty() && WriteSleep !in granted) {
            sleepMinuteRepository.upsert(sleepMinutes, salt)
            withheld += WriteSleep
        } else {
            estimateSleep(sleepMinutes, nightsWithRealStages, salt)
        }
        if (withheld.isNotEmpty()) GarminLog.log("[GARMIN-IMPORT] ${withheld.size} record type(s) not granted, left out")
        return withheld
    }

    /**
     * Stores the minutes of a watch that does not stage sleep, then estimates
     * every night they touch. Room first: if Health Connect fails, the next
     * sync re-runs from what was stored.
     */
    private suspend fun estimateSleep(
        minutes: List<GarminSleepMinute>,
        nightsWithRealStages: Set<LocalDate>,
        salt: String,
    ) {
        if (minutes.isEmpty()) return
        sleepMinuteRepository.upsert(minutes, salt)
        GarminLog.log("[GARMIN-IMPORT] stored ${minutes.size} sleep minutes")

        // The offset of a night's last minute keys its window; a DST change mid-night shifts it an hour at most.
        val offsets = mutableMapOf<LocalDate, ZoneOffset>()
        for (minute in minutes.sortedBy { it.time }) {
            val night = SleepStageEstimator.nightDateOf(minute.time, minute.zoneOffset) ?: continue
            offsets[night] = minute.zoneOffset
        }
        sleepEstimation.estimateNights(offsets.keys, offsets, nightsWithRealStages, salt)

        sleepMinuteRepository.pruneBefore(Instant.now().minusMillis(SleepMinuteRetention.inWholeMilliseconds))
    }

    /**
     * The granted records in one batched insert; returns the permissions of the rest.
     * Duplicate-id rejections are retried one by one and count as success.
     */
    private suspend fun writeRecords(records: List<Record>, granted: Set<String>): Set<String> {
        // Several files can restate the same record; the last occurrence wins.
        val (deduped, withheld) = partitionByWriteGrant(
            records.associateBy { it.metadata.clientRecordId ?: it }.values.toList(),
            granted,
        )
        if (deduped.isEmpty()) return withheld
        val written = importRepository.insertSkippingPresent(deduped)
        GarminLog.log(
            if (written == deduped.size) {
                "[GARMIN-IMPORT] wrote $written Health Connect records"
            } else {
                "[GARMIN-IMPORT] wrote $written/${deduped.size} records (rest already present)"
            },
        )
        return withheld
    }

    private suspend fun writeCounters(counters: FitMonitoringCounters, granted: Set<String>, salt: String): Set<String> {
        if (counters.isEmpty) return emptySet()
        val mapped = fitMonitoringCounterRecords(counters, previous = watermarkStore.load(salt), salt = salt)
        if (mapped.records.isEmpty()) {
            // Nothing new, but the cursor moved. Persisting it keeps a re-sync
            // from re-walking the same minutes, and nothing is lost.
            watermarkStore.save(mapped.watermarks, salt)
            return emptySet()
        }
        val withheld = writeRecords(mapped.records, granted)
        // Only once the records are in, or those minutes would be skipped for good. One mark
        // covers steps, distance and calories together, so it moves even when one of them was
        // not granted: those minutes of that type stay unwritten, and the card says so.
        watermarkStore.save(mapped.watermarks, salt)
        GarminLog.log(
            "[GARMIN-IMPORT] wrote ${mapped.records.size} intraday counter " +
                "records across ${mapped.watermarks.size} day(s)",
        )
        return withheld
    }

    /** The watch-only metrics one decoded file carried. */
    private fun watchOnlySamples(wellness: FitWellness): List<GarminWellnessSample> = buildList {
        fun add(metric: GarminWellnessMetric, at: Instant, value: Long) {
            add(GarminWellnessSample(metric = metric, time = at, value = value))
        }

        // VO2 max is absent on purpose: Health Connect has a type for it.
        val metrics = wellness.metrics
        val metricsAt = metrics?.time
        if (metrics != null && metricsAt != null) {
            metrics.recoveryTimeMinutes?.let {
                add(GarminWellnessMetric.RECOVERY_TIME, metricsAt, it.toLong())
            }
            metrics.trainingReadiness?.let {
                add(GarminWellnessMetric.TRAINING_READINESS, metricsAt, it.toLong())
            }
            metrics.trainingLoadAcute?.let {
                add(GarminWellnessMetric.TRAINING_LOAD_ACUTE, metricsAt, it.toLong())
            }
            metrics.trainingLoadChronic?.let {
                add(GarminWellnessMetric.TRAINING_LOAD_CHRONIC, metricsAt, it.toLong())
            }
        }

        // daily_sleep arrives in the metrics file. Keyed to the night's end.
        val daily = wellness.dailySleep
        val dailyAt = daily?.endTime
        if (daily != null && dailyAt != null) {
            daily.score?.let { add(GarminWellnessMetric.SLEEP_SCORE, dailyAt, it.toLong()) }
            daily.awakeDuration?.let { awake ->
                add(GarminWellnessMetric.SLEEP_AWAKE_SECONDS, dailyAt, awake.seconds)
                GarminLog.log(
                    "[FIT-SLEEP] watch awake_duration=${awake.toMinutes()}m " +
                        "for the night ending $dailyAt",
                )
            }
            daily.pressure?.let { add(GarminWellnessMetric.SLEEP_PRESSURE, dailyAt, it.toLong()) }
        }

        // Sleep Coach.
        val demand = wellness.sleepDemand
        val demandAt = demand?.time
        if (demand != null && demandAt != null) {
            demand.normal?.let {
                add(GarminWellnessMetric.SLEEP_NEED_NORMAL_MINUTES, demandAt, it.toMinutes())
            }
            demand.demand?.let {
                add(GarminWellnessMetric.SLEEP_NEED_MINUTES, demandAt, it.toMinutes())
            }
        }

        // The watch's own verdict on a night, keyed to when the night began.
        val sleep = wellness.sleep
        if (sleep != null) {
            sleep.overallScore?.let {
                add(GarminWellnessMetric.SLEEP_SCORE, sleep.start, it.toLong())
            }
            sleep.awakeningsCount?.let {
                add(GarminWellnessMetric.SLEEP_AWAKENINGS, sleep.start, it.toLong())
            }
        }

        // Health Snapshot stress and Body Battery: the same quantity as the all-day
        // series, measured deliberately. The (metric, time) key keeps them apart.
        val snapshot = wellness.healthSnapshot
        if (snapshot != null) {
            for ((at, value) in snapshot.stress) {
                add(GarminWellnessMetric.STRESS, at, value.toLong())
            }
            for ((at, value) in snapshot.bodyEnergy) {
                add(GarminWellnessMetric.BODY_ENERGY, at, value.toLong())
            }
        }

        val monitoring = wellness.monitoring ?: return@buildList
        for ((at, value) in monitoring.stress) {
            add(GarminWellnessMetric.STRESS, at, value.toLong())
        }
        for ((at, value) in monitoring.bodyEnergy) {
            add(GarminWellnessMetric.BODY_ENERGY, at, value.toLong())
        }
        for ((at, value) in monitoring.moderateMinutes) {
            add(GarminWellnessMetric.MODERATE_MINUTES, at, value.toLong())
        }
        for ((at, value) in monitoring.vigorousMinutes) {
            add(GarminWellnessMetric.VIGOROUS_MINUTES, at, value.toLong())
        }
    }
}

/** How long stored sleep minutes are kept. Long enough for a late-synced night. */
internal val SleepMinuteRetention = 45.days

private val WriteSleep = HealthPermission.getWritePermission(SleepSessionRecord::class)
