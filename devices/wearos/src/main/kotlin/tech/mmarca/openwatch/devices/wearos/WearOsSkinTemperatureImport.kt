package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.TemperatureDelta
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import tech.mmarca.healthcalculations.stats.medianOrNull
import tech.mmarca.openwatch.devices.core.saltedRecordId

/**
 * Turns the watch's skin temperature minutes (the Samsung platform's, recorded
 * while sleep is held) into Health Connect records: one `SkinTemperatureRecord`
 * per night run, wrist, keyed `wearos_skin_<run start millis>`.
 *
 * Health Connect stores a skin temperature as deltas from a baseline, which is
 * how the measure is meant to be read: a night warmer or cooler than the
 * wearer's own usual. The baseline is the median of the other nights'
 * medians once there are [MIN_BASELINE_NIGHTS] of them. Before that a night
 * has no baseline and is not written, only remembered for the baseline: a
 * night against its own median would read as a measured "no change" (deltas
 * around zero), where the change is in fact unknown. The baseline counts
 * nights ([nightOf]), not runs: a night the tracker dropped out of several
 * times, or one that reached the phone over several syncs, is one night,
 * never compared with its own fragments.
 *
 * Pure, so it is testable without a device.
 */
object WearOsSkinTemperatureImport {

    const val CLIENT_ID_PREFIX = "wearos_skin_"

    /** A gap longer than this starts a new run: the watch came off, or the night ended. */
    val MAX_GAP: Duration = Duration.ofMinutes(30)

    /** A run shorter than this is a nap or a test, not a night's temperature. */
    val MIN_RUN: Duration = Duration.ofMinutes(30)

    /** A run whose last minute is this old is finished; a younger one may still grow. */
    val SETTLE: Duration = Duration.ofMinutes(30)

    const val MIN_BASELINE_NIGHTS = 3

    /** The nights the baseline remembers. */
    const val MAX_BASELINE_NIGHTS = 30

    val writePermission: String = HealthPermission.getWritePermission(SkinTemperatureRecord::class)

    /** One night's minutes, in time order. */
    data class Run(val minutes: List<WearOsSkinMinute>) {
        val start: Instant get() = minutes.first().time
        val end: Instant get() = minutes.last().time
        val median: Double get() = checkNotNull(minutes.map { it.celsius }.medianOrNull())
    }

    /** The minutes cut into runs at every gap over [MAX_GAP]. */
    fun runs(minutes: List<WearOsSkinMinute>): List<Run> {
        val sorted = minutes.sortedBy { it.time }
        val runs = ArrayList<Run>()
        var current = ArrayList<WearOsSkinMinute>()
        for (minute in sorted) {
            val last = current.lastOrNull()
            if (last != null && Duration.between(last.time, minute.time) > MAX_GAP) {
                runs += Run(current)
                current = ArrayList()
            }
            current += minute
        }
        if (current.isNotEmpty()) runs += Run(current)
        return runs
    }

    /** The runs that are over at [now] and long enough to count. */
    fun finished(runs: List<Run>, now: Instant): List<Run> =
        runs.filter { Duration.between(it.end, now) >= SETTLE && Duration.between(it.start, it.end) >= MIN_RUN }

    /**
     * One night's median for the baseline, taken over [minutes] minutes; 0 where they were not
     * kept. [night] is null for a median remembered before nights had dates.
     */
    data class NightMedian(val night: LocalDate?, val celsius: Double, val minutes: Int = 0)

    /**
     * The night a run belongs to: the local date of its start, counted from noon to noon, so the
     * runs of one night either side of midnight, and a nap before it, share one night.
     */
    fun nightOf(run: Run, zone: ZoneId): LocalDate = run.start.atZone(zone).minusHours(12).toLocalDate()

    /**
     * The baseline for a run of [night]: the median of the other nights' medians once there are
     * [MIN_BASELINE_NIGHTS], else null (unknown).
     */
    fun baseline(night: LocalDate, medians: List<NightMedian>): Double? {
        val others = medians.filter { it.night != night }
        return if (others.size >= MIN_BASELINE_NIGHTS) others.map { it.celsius }.medianOrNull() else null
    }

    /**
     * [medians] with [runs] of [night] counted in, and only the newest [MAX_BASELINE_NIGHTS] kept.
     * A new night joins once, with the median of all the runs' minutes. A night already
     * remembered, whose earlier runs came with an earlier sync, takes these in by their length:
     * the phone keeps no minutes, so the night's median becomes the mean of the two medians,
     * weighted by their minutes. An earlier part kept without its minutes counts as much as what
     * arrives now. It stays one night, in its place, either way.
     */
    fun remember(medians: List<NightMedian>, night: LocalDate, runs: List<Run>): List<NightMedian> {
        val celsius = runs.flatMap { run -> run.minutes.map { it.celsius } }
        val median = celsius.medianOrNull() ?: return medians
        val known = medians.indexOfFirst { it.night == night }
        if (known < 0) return (medians + NightMedian(night, median, celsius.size)).takeLast(MAX_BASELINE_NIGHTS)
        val earlier = medians[known]
        val earlierMinutes = if (earlier.minutes > 0) earlier.minutes else celsius.size
        val minutes = earlierMinutes + celsius.size
        val merged = (earlier.celsius * earlierMinutes + median * celsius.size) / minutes
        return medians.toMutableList().also { it[known] = NightMedian(night, merged, minutes) }
    }

    /** The record for [run] against [baselineCelsius], or null when Health Connect would refuse it. */
    fun record(run: Run, baselineCelsius: Double, zone: ZoneId, version: Long, salt: String = ""): SkinTemperatureRecord? = try {
        SkinTemperatureRecord(
            startTime = run.start,
            startZoneOffset = zone.rules.getOffset(run.start),
            // The last minute's row stands for that whole minute.
            endTime = run.end.plusSeconds(60),
            endZoneOffset = zone.rules.getOffset(run.end),
            metadata = Metadata.autoRecorded(
                device = Device(type = Device.TYPE_WATCH),
                clientRecordId = clientRecordId(run, salt),
                clientRecordVersion = version,
            ),
            deltas = run.minutes.map {
                SkinTemperatureRecord.Delta(it.time, TemperatureDelta.celsius(it.celsius - baselineCelsius))
            },
            baseline = Temperature.celsius(baselineCelsius),
            measurementLocation = SkinTemperatureRecord.MEASUREMENT_LOCATION_WRIST,
        )
    } catch (_: IllegalArgumentException) {
        null
    }

    fun clientRecordId(run: Run, salt: String = ""): String = saltedRecordId("$CLIENT_ID_PREFIX${run.start.toEpochMilli()}", salt)
}
