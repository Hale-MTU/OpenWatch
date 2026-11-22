package tech.mmarca.openwatch.devices.wearos

import tech.mmarca.healthcalculations.preferences.BodyProfile
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.core.presentation.isPermissionFailure
import tech.mmarca.openwatch.data.repository.HealthRecordStore
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.data.repository.BodyProfileProvider
import tech.mmarca.openwatch.data.repository.OpenWatchPreferences
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncPhase
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncPort
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncProgress
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncResult
import tech.mmarca.openwatch.devices.core.sync.SyncFailure
import tech.mmarca.openwatch.devices.core.sync.SyncTrigger
import tech.mmarca.openwatch.devices.core.sync.HealthSeries
import tech.mmarca.openwatch.data.repository.RecordWrite
import tech.mmarca.openwatch.data.repository.insertNewer
import tech.mmarca.openwatch.data.repository.insertWithOutcomes
import tech.mmarca.healthcalculations.insights.sleepNightWindow
import tech.mmarca.healthcalculations.insights.WearSleepEstimator
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.healthcalculations.model.WearSleepMinute
import tech.mmarca.openwatch.data.repository.replaceImportedRecord
import tech.mmarca.openwatch.healthconnect.HealthConnectReads
import tech.mmarca.openwatch.healthconnect.HealthConnectSyncDisabledException
import tech.mmarca.openwatch.healthconnect.isHealthConnectRateLimit
import tech.mmarca.healthcalculations.insights.AscentCredit
import tech.mmarca.openwatch.devices.core.sync.syncBudgetSpent
import tech.mmarca.openwatch.wearlink.WearLinkSince

/**
 * Pulls what the OpenWatch Wear OS app recorded and writes it to Health
 * Connect. The [DeviceSyncPort] for `(WATCH, WEAROS)` devices.
 *
 * Every series comes with one request, `SINCE`, by its key ([WearOsSeries]).
 * Heart rate first: one page at a time from the watch's sequence cursor, each
 * page written before the next is asked for, and the cursor advanced after
 * each write. A sync that dies mid-way therefore loses nothing: the next one
 * resumes at the last page written, and a page written twice upserts. A
 * sample the watch wrote late, behind those already sent, comes with the next
 * page. The first pull hands the time cursor of earlier builds over to the
 * sequence ([WearOsSeriesSync]), so nothing already written is pulled again.
 *
 * Then steps, the same way: a minute the watch rewrote as late steps arrived
 * comes again by the watch's cursor, and a minute that grew replaces its
 * record. Without the Health Connect grant to write
 * steps, the steps stay on the watch and the sync goes on. Two estimates
 * come with the steps: cadence, and walking energy when the body weight is
 * known ([WearOsStepsImport]). If the user withheld the write permission for
 * one, it is skipped and the sync goes on. Then floors and their elevation gain, the
 * same way; only minutes with steps are written ([WearOsFloorsImport]), so
 * they wait for the steps grant too.
 *
 * Then sleep: the minutes from the start of the night the sleep cursor sits
 * in, every night those minutes touch estimated whole and written as one
 * session per night, replacing the earlier estimate. A night that already
 * holds a session from anywhere else, the vendor's app or the user's own
 * entry, is left alone. Naps are written the same way, one session each
 * under the id of its onset, and a nap never replaces a session from
 * elsewhere ([WearOsSleepImport.naps]). Each finished night with sleep also gives a nocturnal
 * resting heart rate ([WearOsRestingHeartRateImport]). The app averages the
 * values of a calendar day, so a day that holds one from elsewhere gets none
 * from here, and one that turns up later removes ours. Without the grants to
 * read and write it, or the right to read other apps' data, none is written.
 * The sleep cursor moves once the nights are written.
 *
 * If the user withheld the write permission for an estimate, it is skipped
 * and the sync goes on, as a [DeviceSyncResult.Succeeded.skipped] entry.
 *
 * [DeviceSyncResult.Succeeded.written] counts the heart rate samples, the
 * minutes of steps and the nights written ([WearOsSyncProgress]).
 */
@Singleton
class WearOsWatchSyncService(
    private val nodePort: WearOsNodePort,
    private val cursors: WearOsSyncCursorStore,
    private val importRepository: HealthRecordStore,
    private val bleDeviceRepository: BleDeviceRepository,
    private val healthConnect: HealthConnectReads,
    private val linkStore: WearOsLinkStore,
    /** Body weight for the walking energy: Health Connect's latest, else the profile's. Null when neither has one. */
    private val weightKg: suspend () -> Double?,
    private val clock: () -> Instant,
    private val zone: () -> ZoneId,
    /** Whether the user chose to write the watch's breathing rate; off by default, not yet checked against a reference. */
    private val writeRespiratoryRate: () -> Boolean = { false },
    /** The body for a workout's calories: sex, age and weight. Strict like [weightKg]. */
    private val profile: suspend () -> BodyProfile = { BodyProfile(weightKg = weightKg()) },
    /**
     * The body for the watch's heart rate zones: the declared profile, no Health Connect
     * read, since the zones take no weight and a sync with nothing to estimate reads none.
     */
    private val zoneProfile: suspend () -> BodyProfile = { BodyProfile() },
) : DeviceSyncPort {

    @Inject
    constructor(
        nodePort: WearOsNodePort,
        cursors: WearOsSyncCursorStore,
        importRepository: HealthRecordStore,
        bleDeviceRepository: BleDeviceRepository,
        healthConnect: HealthConnectReads,
        linkStore: WearOsLinkStore,
        bodyProfile: BodyProfileProvider,
        preferences: OpenWatchPreferences,
    ) : this(
        nodePort, cursors, importRepository, bleDeviceRepository, healthConnect, linkStore,
        // Strict by construction: a failed Health Connect read throws, it never passes for "no weight".
        { bodyProfile.resolve().weightKg },
        Instant::now, ZoneId::systemDefault,
        { preferences.writeRespiratoryRate },
        { bodyProfile.resolve() },
        { bodyProfile.declared() },
    )

    private val syncMutex = Mutex()

    private val nightSeries = WearOsNightSeriesSync(
        importRepository = importRepository,
        healthConnect = healthConnect,
        cursors = cursors,
        clock = clock,
        zone = zone,
        writeRespiratoryRate = writeRespiratoryRate,
    )

    private val workouts = WearOsWorkoutSync(
        importRepository = importRepository,
        cursors = cursors,
        granted = { healthConnect.grantedPermissions() },
        zone = zone,
    )

    private val workoutEnergy = WearOsWorkoutEnergy(
        healthConnect = healthConnect,
        profile = profile,
        insert = { records -> insertEstimates(records, "workout energy") },
        clock = clock,
        zone = zone,
    )

    private val zones by lazy { WearOsZoneSync(healthConnect, nodePort, zoneProfile, clock, zone) }

    private val linkRetry = object : WearOsLinkRetry {
        override suspend fun <T> run(pull: suspend () -> T): T = retryOnce(pull)
    }

    private val reader = WearOsSeriesReader(nodePort, linkRetry)

    init {
        WearOsLog.installLogcatSink()
    }

    override fun canSync(device: BleSensorDevice): Boolean = device.isWearOs

    override suspend fun sync(
        device: BleSensorDevice,
        trigger: SyncTrigger,
        onProgress: ((DeviceSyncProgress) -> Unit)?,
    ): DeviceSyncResult = syncMutex.withLock {
        syncSerially(device, onProgress)
    }

    private suspend fun syncSerially(
        device: BleSensorDevice,
        onProgress: ((DeviceSyncProgress) -> Unit)?,
    ): DeviceSyncResult {
        onProgress?.invoke(DeviceSyncProgress(DeviceSyncPhase.HANDSHAKE))
        val progress = WearOsSyncProgress(onProgress, salt = device.idSalt)
        // Between series, past their cursors, a spent budget ends the run; the rest is next.
        suspend fun spent() = syncBudgetSpent().also { if (it) progress.moreRemains = true }
        try {
            run series@{
                if (!pullHeartRate(device, progress)) return notPaired(device)
                if (spent()) return@series
                val steps = pullSteps(device, progress) ?: return notPaired(device)
                // The floors filter needs the steps, so floors wait when the steps were not pulled.
                if (STEPS_WRITE_PERMISSION !in progress.withheldPermissions && !pullAscent(device, progress, steps)) {
                    return notPaired(device)
                }
                if (spent()) return@series
                if (!pullSleep(device, progress)) return notPaired(device)
                val night = nightSeries.sync(device, reader) ?: return notPaired(device)
                progress.skipped += night.skipped
                if (spent()) return@series
                if (!pullWorkouts(device, progress)) return notPaired(device)
                zones.sync(device, linkRetry)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (failure: WearOsLinkFailure) {
            linkStore.record(device.id, WearOsLinkSnapshot(failure.toAppStatus(), checkedAt = clock()))
            WearOsLog.log("sync failed: ${failure.message}")
            val partWay = progress.hasWritten && failure is WearOsLinkFailure.NoAnswer
            return DeviceSyncResult.Failed(if (partWay) SyncFailure.Interrupted else failure.toSyncFailure(), progress.outcome().takeIf { partWay })
        } catch (error: SecurityException) {
            return DeviceSyncResult.Failed(permissionError(error))
        } catch (error: Exception) {
            if (error.isPermissionFailure()) return DeviceSyncResult.Failed(permissionError(error))
            if (error is HealthConnectSyncDisabledException) return DeviceSyncResult.Failed(SyncFailure.SyncPaused)
            if (error.isHealthConnectRateLimit()) return DeviceSyncResult.Failed(SyncFailure.RateLimited, progress.outcome())
            WearOsLog.log("sync failed: $error")
            return DeviceSyncResult.Failed(SyncFailure.Unknown(error))
        }
        linkStore.record(device.id, WearOsLinkSnapshot(WearOsAppStatus.APP_RUNNING, checkedAt = clock()))

        // A run its budget cut short is not a sync of everything.
        if (!progress.moreRemains) bleDeviceRepository.markSynced(device.id, clock())
        return progress.outcome()
    }

    /**
     * False when no bonded watch matches. The samples written are counted on [progress]. The watch
     * sends each sample once by its sequence number, so the hour records, keyed on their first
     * sample, never hold one twice; the time cursor only hands over and keeps the newest time.
     */
    private suspend fun pullHeartRate(device: BleSensorDevice, progress: WearOsSyncProgress): Boolean {
        val series = WearOsSeries.HEART_RATE
        val start = cursors.seriesCursor(device.id, series.key) ?: WearLinkSince.afterTime(cursors.heartRateCursor(device.id).toEpochMilli())
        return reader.pages(device, series, start) { page ->
            progress.page()
            if (page.values.isNotEmpty()) {
                importRepository.insertImportedRecords(WearOsHeartRateImport.records(page.values, progress.salt))
                progress.written += page.values.size
                val newest = page.values.maxOf { it.time }
                if (newest.isAfter(cursors.heartRateCursor(device.id))) cursors.setHeartRateCursor(device.id, newest)
            }
            cursors.setSeriesCursor(device.id, series.key, page.cursor)
            true
        }
    }

    /**
     * The step minutes pulled, for the floors. Null when no bonded watch
     * matches. The minutes written are counted on [progress]. A minute comes
     * once per write on the watch: the watch rewrites its newest minute as
     * late steps arrive, and the rewrite comes with the next page.
     */
    private suspend fun pullSteps(device: BleSensorDevice, progress: WearOsSyncProgress): List<WearOsStepMinute>? {
        val granted = healthConnect.grantedPermissions()
        val withheld = STEPS_SYNC_PERMISSIONS - granted
        if (STEPS_WRITE_PERMISSION in withheld) {
            // Nothing pulled, so whether a minute would need the energy grants is unknown; the steps come first.
            progress.withheld(HealthSeries.STEPS, setOf(STEPS_WRITE_PERMISSION))
            WearOsLog.log("steps and floors: not pulled, Health Connect permission missing")
            return emptyList()
        }
        // In the background without that grant Health Connect shows this app its own records only: another
        // app's weight and exercise sessions are invisible, so the energy would be filtered against nothing.
        val seesOtherApps = healthConnect.readsOtherAppsDataNow()
        // Without the exercise read grant every session read fails, so it gates the energy like the write grant.
        val writeEnergy = ACTIVE_CALORIES_WRITE_PERMISSION !in withheld && EXERCISE_READ_PERMISSION !in withheld && seesOtherApps
        if (ACTIVE_CALORIES_WRITE_PERMISSION in withheld || EXERCISE_READ_PERMISSION in withheld) {
            WearOsLog.log("walking energy: not written, Health Connect permission missing")
        } else if (!seesOtherApps) {
            WearOsLog.log("walking energy: not written, other apps' sessions are not readable in the background")
        }
        val cursor = cursors.stepsCursor(device.id)
        val start = cursors.seriesCursor(device.id, WearOsSeries.STEPS.key) ?: handOverMinutes(cursor)
        val allMinutes = ArrayList<WearOsStepMinute>()
        var pulledSteps = 0
        var energyMinutes = 0
        var energyKcal = 0.0
        // The energy grants are reported only when a pulled minute would have used them.
        var energyNeeded = false
        // Read once, and only when a minute would need it. A failed read is not "no weight": the card would ask
        // for one the user has.
        var weightLookup: Result<Double?>? = null
        suspend fun knownWeight(): Result<Double?> = weightLookup ?: bodyWeightKg().also { weightLookup = it }

        // A page's batches share one read of the exercise sessions over the whole page, made when a batch first needs it.
        var page: List<WearOsStepMinute> = emptyList()
        var pageSessionsRead = false
        var pageSessions: List<ClosedRange<Instant>>? = null
        suspend fun sessionsOfPage(): List<ClosedRange<Instant>>? {
            if (!pageSessionsRead) {
                // Asked again per page: a sync begun in the foreground goes on after the user leaves the app.
                pageSessions = if (healthConnect.readsOtherAppsDataNow()) exerciseSessions(page) else null
                pageSessionsRead = true
            }
            return pageSessions
        }

        suspend fun writeBatch(batch: List<WearOsStepMinute>) {
            if (batch.isEmpty()) return
            // A minute is estimated when its steps were written; see writeSteps for the minutes past the cursor.
            val due = writeSteps(batch, cursor, progress)
            if (!WearOsStepsImport.hasWalkingEnergy(due)) return
            if (!writeEnergy) {
                // The sessions cannot be read here, so whether a recorded workout covers these minutes is unknown.
                energyNeeded = true
                return
            }
            // Sessions before the weight: minutes inside a recorded workout get no estimate, so they need no weight.
            val sessions = sessionsOfPage() ?: return
            val minutes = WearOsStepsImport.outsideExerciseSessions(due, sessions)
            if (!WearOsStepsImport.hasWalkingEnergy(minutes)) return
            energyNeeded = true
            val weightKg = knownWeight().getOrElse { return }
            if (weightKg == null) {
                // A weight in Health Connect is out of reach without this grant, so the card asks for it.
                if (progress.noBodyWeight(readWithheld = WEIGHT_READ_PERMISSION !in granted)) {
                    WearOsLog.log("steps: no body weight, so no walking energy")
                }
                return
            }
            val energy = WearOsStepsImport.activeCaloriesRecords(minutes, weightKg, zone(), progress.salt)
            // Counted as written, not as tried: a minute refused as present adds nothing.
            val written = energy.zip(insertEstimates(energy, "walking energy")).filter { (_, outcome) -> outcome == RecordWrite.WRITTEN }
            energyMinutes += written.size
            energyKcal += written.sumOf { (record, _) -> record.energy.inKilocalories }
        }

        val found = reader.pages(device, WearOsSeries.STEPS, start) { pulled ->
            progress.page()
            // Each minute once, so every builder below works on the same minutes.
            page = WearOsStepsImport.latestPerMinute(pulled.values)
            allMinutes += page
            pageSessionsRead = false
            val newest = page.maxOfOrNull { it.start }
            if (newest != null) {
                // A minute the watch rewrote comes again; the write reads whether it grew.
                writeBatch(page)
                pulledSteps += page.sumOf { it.steps }
                if (newest.isAfter(cursors.stepsCursor(device.id))) cursors.setStepsCursor(device.id, newest)
            }
            cursors.setSeriesCursor(device.id, WearOsSeries.STEPS.key, pulled.cursor)
            true
        }
        if (!found) return null
        if (energyNeeded && withheld.isNotEmpty()) {
            progress.withheld(HealthSeries.WALKING_ENERGY, withheld)
        }
        val weight = weightLookup?.getOrNull()
        // The watch has no screen for these yet; testers read this line.
        WearOsLog.log(
            "steps: ${progress.stepMinutes} minute(s) written, $pulledSteps steps pulled (a rewritten minute counts again); " +
                if (energyMinutes == 0) {
                    "no walking energy written"
                } else {
                    "walking energy ${"%.1f".format(Locale.ROOT, energyKcal)} kcal written over $energyMinutes minute(s) at $weight kg"
                },
        )
        return allMinutes
    }

    /**
     * False when no bonded watch matches. Ascent is pulled the way steps are,
     * by the watch's cursor, and written as floors only for minutes with steps
     * around them; see [WearOsFloorsImport]. The steps of those minutes come
     * from [steps], this sync's, or, for a minute it did not pull, from the
     * watch ([WearOsStepContext]).
     */
    private suspend fun pullAscent(device: BleSensorDevice, progress: WearOsSyncProgress, steps: List<WearOsStepMinute>): Boolean {
        val cursor = cursors.ascentCursor(device.id)
        val start = cursors.seriesCursor(device.id, WearOsSeries.ASCENT.key) ?: handOverMinutes(cursor)
        val context = WearOsStepContext(reader, device, steps)
        var climbed = 0.0
        var credited = 0.0
        var lost = false
        val found = reader.pages(device, WearOsSeries.ASCENT, start) { pulled ->
            progress.page()
            val minutes = pulled.values
            val newest = minutes.maxOfOrNull { it.start }
            if (newest != null) {
                val around = context.around(minutes) ?: run {
                    lost = true
                    return@pages false
                }
                val records = WearOsFloorsImport.records(minutes, around, progress.salt)
                // A minute seen before apart: its records may be there already, and it must not make the rest fall back to one at a time.
                for (part in records.partition { (it as FloorsClimbedRecord).startTime.isAfter(cursor) }.toList()) {
                    insertEstimates(part, "floors")
                }
                val elevation = WearOsFloorsImport.elevationRecords(minutes, around, progress.salt)
                for (part in elevation.partition { (it as ElevationGainedRecord).startTime.isAfter(cursor) }.toList()) {
                    insertEstimates(part, "elevation")
                }
                climbed += minutes.sumOf { it.metres }
                credited += records.sumOf { (it as FloorsClimbedRecord).floors } * AscentCredit.METRES_PER_FLOOR
                if (newest.isAfter(cursors.ascentCursor(device.id))) cursors.setAscentCursor(device.id, newest)
            }
            cursors.setSeriesCursor(device.id, WearOsSeries.ASCENT.key, pulled.cursor)
            true
        }
        if (!found || lost) return false
        // The watch has no screen for these yet; testers read this line.
        WearOsLog.log(
            "ascent: ${"%.1f".format(Locale.ROOT, credited)} of ${"%.1f".format(Locale.ROOT, climbed)} m climbed written as floors; " +
                "the rest had too few steps (elevators, weather)",
        )
        return true
    }

    /**
     * The steps of [minutes], then the cadence of the minutes due an estimate:
     * those Health Connect did not already hold at that count, and every
     * minute past [stepsCursor], which a sync that failed after its steps may
     * have left without one. Returns the minutes due, for the walking energy.
     */
    private suspend fun writeSteps(minutes: List<WearOsStepMinute>, stepsCursor: Instant, progress: WearOsSyncProgress): List<WearOsStepMinute> {
        val records = WearOsStepsImport.records(minutes, zone(), progress.salt)
        // Read first: the platform's Health Connect takes an unchanged minute again without a word.
        val changed = records.zip(importRepository.insertNewer(records))
            .filter { (_, outcome) -> outcome == RecordWrite.WRITTEN }
            .mapTo(HashSet()) { (record, _) -> record.startTime }
        progress.stepMinutes += changed.size
        val due = minutes.filter { it.start in changed || it.start.isAfter(stepsCursor) }
        // Health Connect files cadence under the steps grant.
        insertEstimates(WearOsStepsImport.cadenceRecords(due, zone(), progress.salt), "cadence")
        return due
    }

    /**
     * Writes records derived from the steps. They are optional: a failure is
     * logged and skipped, so it never fails the sync and keeps sleep from
     * being pulled. Returns what became of each record, or nothing when the
     * write failed.
     */
    private suspend fun insertEstimates(records: List<Record>, what: String): List<RecordWrite> = try {
        importRepository.insertWithOutcomes(records)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        WearOsLog.log("$what: not written: $error")
        emptyList()
    }

    /** A written workout's active calories ([WearOsWorkoutEnergy]); a missing grant or weight is reported as the walking energy's. */
    private suspend fun writeWorkoutEnergy(workout: WearOsWorkout, progress: WearOsSyncProgress) {
        when (val outcome = workoutEnergy.write(workout, progress.salt)) {
            WearOsWorkoutEnergy.Outcome.Written, WearOsWorkoutEnergy.Outcome.Skipped -> Unit
            WearOsWorkoutEnergy.Outcome.PermissionMissing -> {
                progress.withheld(HealthSeries.WALKING_ENERGY, setOf(WearOsWorkoutImport.energyPermission))
            }
            is WearOsWorkoutEnergy.Outcome.NoWeight -> progress.noBodyWeight(outcome.weightReadWithheld)
        }
    }

    /** The weight for the walking energy; a failure when it could not be read. It is optional, so that never fails the sync. */
    private suspend fun bodyWeightKg(): Result<Double?> = try {
        Result.success(weightKg())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        WearOsLog.log("steps: could not read body weight for walking energy: $error")
        Result.failure(error)
    }

    /** False when no bonded watch matches. */
    private suspend fun pullSleep(device: BleSensorDevice, progress: WearOsSyncProgress): Boolean {
        val cursor = cursors.sleepCursor(device.id)
        val since = WearOsSleepImport.pullStart(cursor, zone().rules.getOffset(cursor))
        val minutes = reader.newerThan(device, WearOsSeries.SLEEP_MINUTES, since) { progress.page() } ?: return false
        val newest = minutes.maxOfOrNull { it.time } ?: return true

        var nights = 0
        val restingHeartRate = RestingHeartRateAccess(
            withheld = RESTING_HEART_RATE_PERMISSIONS - healthConnect.grantedPermissions(),
            seesOtherApps = healthConnect.readsOtherAppsDataNow(),
        )
        for ((night, offset) in WearOsSleepImport.touchedNights(minutes, cursor)) {
            val (from, to) = sleepNightWindow(night, offset)
            val estimated = WearOsSleepImport.night(minutes, night, offset)
            if (estimated == null) {
                WearOsLog.log("$night: no night in the minutes")
                continue
            }
            WearOsLog.log("$night: ${estimated.summary()}")
            val record = WearOsSleepImport.record(estimated, night, offset, version = clock().toEpochMilli(), salt = progress.salt)
            if (record == null) {
                WearOsLog.log("$night: nothing counted as sleep")
                continue
            }
            // Before the session check: a vendor that owns the session may not own the resting heart rate.
            writeRestingHeartRate(minutes, estimated, night, offset, restingHeartRate, progress)
            if (hasForeignRecord(SleepSessionRecord::class, from, to, WearOsSleepImport::isOwnRecordId)) {
                WearOsLog.log("$night: Health Connect already holds a session from elsewhere, skipping")
                continue
            }
            writeReplacing(record)
            nights++
        }
        val naps = writeNaps(minutes, cursor, progress.salt)
        cursors.setSleepCursor(device.id, newest)
        progress.nights = nights
        WearOsLog.log("sleep: $nights night(s) and $naps nap(s) written from ${minutes.size} minutes")
        return true
    }

    /**
     * One session per nap under its onset's id. A session from anywhere else
     * that overlaps the nap wins. An earlier estimate of the same nap with
     * another onset is removed, so re-estimating never leaves two.
     */
    private suspend fun writeNaps(minutes: List<WearSleepMinute>, cursor: Instant, salt: String): Int {
        var written = 0
        for ((nap, offset) in WearOsSleepImport.naps(minutes, cursor)) {
            val record = WearOsSleepImport.napRecord(nap, offset, version = clock().toEpochMilli(), salt = salt) ?: continue
            if (hasForeignRecord(SleepSessionRecord::class, nap.onset, nap.end, WearOsSleepImport::isOwnRecordId)) {
                WearOsLog.log("nap: Health Connect already holds a session from elsewhere, skipping")
                continue
            }
            removeOtherNapEstimates(nap.onset, nap.end, keepId = WearOsSleepImport.napClientRecordId(nap, salt), salt)
            writeReplacing(record)
            written++
        }
        return written
    }

    /** Deletes this watch's nap sessions in the window other than [keepId]; another watch's stay. A failed read leaves them. */
    private suspend fun removeOtherNapEstimates(from: Instant, to: Instant, keepId: String, salt: String) {
        val stale = ArrayList<String>()
        try {
            healthConnect.forEachSyncRecordPage(SleepSessionRecord::class, from, to) { page ->
                for (record in page) {
                    val id = record.metadata.clientRecordId
                    if (id != null && id != keepId && WearOsSleepImport.isOwnNapId(id, salt)) stale += id
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            WearOsLog.log("nap: could not read earlier estimates: $error")
            return
        }
        importRepository.deleteImportedRecordsByClientIds(SleepSessionRecord::class, stale)
    }


    /**
     * The workouts the wearer recorded on the watch, written as sessions with
     * their route ([WearOsWorkoutSync]). A missing grant leaves them on the
     * watch and the sync goes on. False when no bonded watch matches.
     */
    private suspend fun pullWorkouts(device: BleSensorDevice, progress: WearOsSyncProgress): Boolean {
        val outcome = workouts.sync(
            deviceId = device.id,
            salt = device.idSalt,
            afterWritten = { writeWorkoutEnergy(it, progress) },
        ) { since ->
            retryOnce { nodePort.pullWorkouts(device.address, device.bluetoothName, since) }
        } ?: return false
        if (outcome.withheld.isNotEmpty()) {
            progress.withheld(HealthSeries.WORKOUTS, outcome.withheld)
            WearOsLog.log("workouts: not written, Health Connect permission missing")
        }
        WearOsLog.log("workouts: ${outcome.written} written")
        return true
    }

    /**
     * A page pull again after one link failure. The first connection to a
     * watch that has been dozing in bedtime mode is refused now and then and
     * the next one goes through; a second failure is reported as before.
     */
    private suspend fun <T> retryOnce(pull: suspend () -> T): T = try {
        pull()
    } catch (failure: WearOsLinkFailure.NoAnswer) {
        WearOsLog.log("link failed once (${failure.message}); trying again")
        kotlinx.coroutines.delay(RETRY_DELAY_MILLIS)
        pull()
    }

    /**
     * Writes the night's resting heart rate unless its calendar day already holds one from
     * elsewhere; then it removes ours. A read that fails counts as "held": a second value next to
     * the vendor's would be averaged with it. Optional, so no failure here fails the sync.
     */
    private suspend fun writeRestingHeartRate(
        minutes: List<WearSleepMinute>,
        estimated: WearSleepEstimator.Night,
        night: LocalDate,
        offset: ZoneOffset,
        access: RestingHeartRateAccess,
        progress: WearOsSyncProgress,
    ) {
        val record = WearOsRestingHeartRateImport.record(minutes, estimated, night, offset, version = clock().toEpochMilli(), salt = progress.salt)
        if (record == null) {
            WearOsLog.log("$night: no resting heart rate, the night runs on or has too few readings")
            return
        }
        if (access.withheld.isNotEmpty()) {
            progress.withheld(HealthSeries.RESTING_HEART_RATE, access.withheld)
            WearOsLog.log("$night: resting heart rate not written, Health Connect permission missing")
            return
        }
        if (!access.seesOtherApps) {
            WearOsLog.log("$night: resting heart rate not written, other apps' values are not readable in the background")
            return
        }
        try {
            val (from, to) = WearOsRestingHeartRateImport.dayWindow(estimated.session.end, zone())
            val held = holdings(RestingHeartRateRecord::class, from, to, WearOsRestingHeartRateImport::isOwnRecordId)
            if (held.foreign) {
                WearOsLog.log("$night: Health Connect already holds a resting heart rate from elsewhere, skipping")
                if (held.own) {
                    importRepository.deleteImportedRecordsByClientIds(RestingHeartRateRecord::class, listOf(record.metadata.clientRecordId!!))
                }
                return
            }
            WearOsLog.log("$night: resting heart rate ${record.beatsPerMinute} bpm")
            writeReplacing(record)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            WearOsLog.log("$night: resting heart rate not written: $error")
        }
    }

    /** Whether the window has a record of [type] from another app. A read failure counts as no. */
    private suspend fun hasForeignRecord(
        type: KClass<out Record>,
        from: Instant,
        to: Instant,
        isOwn: (String?) -> Boolean,
    ): Boolean = try {
        holdings(type, from, to, isOwn).foreign
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        WearOsLog.log("Could not read existing ${type.simpleName}: $error")
        false
    }

    /** Who wrote the records of [type] in the window. A failed read throws. */
    private suspend fun holdings(type: KClass<out Record>, from: Instant, to: Instant, isOwn: (String?) -> Boolean): Holdings {
        var foreign = false
        var own = false
        healthConnect.forEachSyncRecordPage(type, from, to) { page ->
            for (record in page) {
                if (isOwn(record.metadata.clientRecordId)) own = true else foreign = true
            }
        }
        return Holdings(foreign, own)
    }

    /** Time ranges of exercise sessions overlapping the step minutes range; null when they could not be read. */
    private suspend fun exerciseSessions(minutes: List<WearOsStepMinute>): List<ClosedRange<Instant>>? {
        if (minutes.isEmpty()) return emptyList()
        // Health Connect filters by a record's bounds: a workout begun before the first minute (or ended after the
        // last) is found only through a window widened both ways. The overlap test in WearOsStepsImport keeps
        // what actually covers a minute.
        val readStart = minutes.minOf { it.start }.minus(SESSION_LOOKAROUND)
        val readEnd = minutes.maxOf { it.start }.plusSeconds(60).plus(SESSION_LOOKAROUND)
        // Strict: the reader turns a failed read into an empty list, which would pass for "no workout".
        return try {
            healthConnect.readExerciseSessionRanges(readStart, readEnd)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            WearOsLog.log("Could not read exercise sessions for walking energy filtering: $error")
            null
        }
    }

    /** A higher version replaces the earlier estimate; a provider that objects has it deleted first. */
    private suspend fun writeReplacing(record: Record) = importRepository.replaceImportedRecord(record)

    private fun notPaired(device: BleSensorDevice): DeviceSyncResult.Failed {
        linkStore.record(device.id, WearOsLinkSnapshot(WearOsAppStatus.NOT_PAIRED, checkedAt = clock()))
        return DeviceSyncResult.Failed(SyncFailure.Described(R.string.settings_watch_wearos_bt_not_paired))
    }

    /** A refused Bluetooth call names the nearby-devices grant; anything else is Health Connect's. */
    private fun permissionError(error: Throwable): SyncFailure =
        if (error is SecurityException && error.message?.contains("BLUETOOTH", ignoreCase = true) == true) {
            SyncFailure.BluetoothPermission
        } else {
            SyncFailure.HealthConnectPermission
        }

    private class Holdings(val foreign: Boolean, val own: Boolean)

    /** What the sync may do with resting heart rates: [withheld] are the grants missing. */
    private class RestingHeartRateAccess(val withheld: Set<String>, val seesOtherApps: Boolean)

    private companion object {
        private const val RETRY_DELAY_MILLIS = 1_500L

        /** Longer than any workout, as `HealthConnectSeries`' lookaround is longer than any series record. */
        private val SESSION_LOOKAROUND: java.time.Duration = java.time.Duration.ofHours(30)

        private val STEPS_WRITE_PERMISSION = HealthPermission.getWritePermission(StepsRecord::class)
        private val ACTIVE_CALORIES_WRITE_PERMISSION = HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class)
        private val EXERCISE_READ_PERMISSION = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
        private val WEIGHT_READ_PERMISSION = HealthPermission.getReadPermission(WeightRecord::class)

        /** Reading tells whether the vendor holds a value for the day; writing is the estimate itself. */
        private val RESTING_HEART_RATE_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getWritePermission(RestingHeartRateRecord::class),
        )

        private val WALKING_ENERGY_PERMISSIONS = setOf(ACTIVE_CALORIES_WRITE_PERMISSION, EXERCISE_READ_PERMISSION)

        /** The grants the steps and their estimates need: the walking energy reads the sessions it leaves out. */
        private val STEPS_SYNC_PERMISSIONS = WALKING_ENERGY_PERMISSIONS + STEPS_WRITE_PERMISSION
    }
}
