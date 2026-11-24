package tech.mmarca.openwatch.healthconnect

import androidx.health.connect.client.records.Record
import java.time.Instant
import java.time.LocalDate
import kotlin.reflect.KClass

/** One local day's totals as Health Connect aggregates them, from every app; null where the read grant is missing or nothing was recorded. */
data class DayTotals(val activeKcal: Double? = null, val distanceMetres: Double? = null)

/**
 * What the devices read of Health Connect: the granted set, whether other apps' data is
 * visible, and the few records an estimate or a sync needs. `HealthConnectGateway` is the one
 * implementation; a device takes this, never the client.
 *
 * Strict by construction: a read that fails throws. Nothing here turns a refusal into an empty
 * answer, because every caller saves what it read.
 */
interface HealthConnectReads {

    /** The health permissions granted to the app now. */
    suspend fun grantedPermissions(): Set<String>

    /**
     * False in the background without the background-read grant. Health Connect then answers
     * a read with this app's own records only, and says nothing. A caller that saves what it
     * read checks this first and skips the pass.
     */
    suspend fun readsOtherAppsDataNow(): Boolean

    /** True when this Health Connect stores skin temperature (a feature of newer versions). */
    fun isSkinTemperatureAvailable(): Boolean

    /** True when this Health Connect stores mindfulness sessions (a feature of newer versions). */
    fun isMindfulnessAvailable(): Boolean

    /**
     * The active energy and the distance of each local day from [first] to [last], from any app,
     * as Health Connect aggregates them. A metric whose read grant is missing is not asked for and
     * stays null; a day with neither is absent.
     */
    suspend fun readDayTotals(first: LocalDate, last: LocalDate): Map<LocalDate, DayTotals>

    /** The highest heart rate Health Connect holds in `[start, end)`, from any app; null without the read grant or a sample. */
    suspend fun readMaxHeartRate(start: Instant, end: Instant): Long?

    /** Every record of [recordType] in `[start, end)`, a page at a time. */
    suspend fun forEachSyncRecordPage(
        recordType: KClass<out Record>,
        start: Instant,
        end: Instant,
        action: suspend (List<Record>) -> Unit,
    )

    /** The time spans of every exercise session overlapping `[start, end)`, from any app. */
    suspend fun readExerciseSessionRanges(start: Instant, end: Instant): List<ClosedRange<Instant>>
}
