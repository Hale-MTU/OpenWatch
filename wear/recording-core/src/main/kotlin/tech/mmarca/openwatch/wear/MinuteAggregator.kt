package tech.mmarca.openwatch.wear

import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.sqrt
import tech.mmarca.healthcalculations.signal.AccelerationMinuteFeatures
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

/**
 * Folds accelerometer readings, heart rate samples, the worn and charging
 * state and screen wake-ups into one `SM` row per clock minute, exactly as
 * `docs/engineering/sleep-minute-features.md` specifies.
 *
 * Readings arrive in g. Pure Kotlin, so a test can feed it. A minute closes
 * once [Config.closeLagMillis] have passed since its start, by which time
 * the sensor hub has flushed every batch that belongs to it.
 */
class MinuteAggregator(
    /** The watch's UTC offset at a minute, seconds. */
    private val offsetSecondsAt: (epochMillis: Long) -> Int,
    /** Whether heart rate is being recorded, read when a minute closes. */
    private val isHeartRateRecording: () -> Boolean,
    private val config: Config = Config(),
) {

    data class Config(
        /** In g. Below this a magnitude change is sensor noise, not a movement. */
        val jerkThresholdG: Double = 0.051,
        /** How much movement one counted reading is worth, at the nominal rate. */
        val movementPerCount: Double = 0.2,
        /** Fewer rate-normalised counts than this is a still minute. */
        val minCounts: Double = 3.0,
        /** The movement a minute saturates at. */
        val maxMovement: Double = 30.0,
        /** Fewer samples than this and the hub dropped part of the minute. */
        val sparseSamples: Int = 150,
        /** A minute closes this long after it started, once every batch for it has arrived. */
        val closeLagMillis: Long = 90_000L,
        /**
         * How long a closed minute still takes a late heart rate. The Samsung platform hands its
         * readings over minutes late with the screen off, after the minute they belong to closed.
         */
        val lateHeartRateMillis: Long = 30 * MINUTE_MILLIS,
    )

    private class Bucket(start: Long) {
        val features = AccelerationMinuteFeatures(start)
        var counts = 0
        val heartRates = ArrayList<Double>(8)
        var charging = false
        var offBody = false
        var screenOn = false
        var heartRateNoContact = false
    }

    /** A closed minute's row and its heart rate samples, kept for [Config.lateHeartRateMillis]. */
    private class Closed(var row: WearLinkProtocol.SleepMinute, val heartRates: ArrayList<Double>)

    private val buckets = TreeMap<Long, Bucket>()
    private val closedRecently = TreeMap<Long, Closed>()
    private val revised = sortedSetOf<Long>()

    /** The start of the newest closed minute: a reading for it or an older one never opens a bucket again. */
    private var closedThrough = Long.MIN_VALUE
    private var lastMagnitude = Double.NaN
    private var lastAngle: Double? = null
    private var worn = true
    private var charging = false

    fun onAcceleration(epochMillis: Long, xG: Double, yG: Double, zG: Double) {
        // A reading for a closed minute is dropped: a bucket of its own would replace the stored row with an emptier one.
        val bucket = openBucketAt(epochMillis) ?: return
        bucket.features.add(epochMillis, xG, yG, zG)
        val magnitude = sqrt(xG * xG + yG * yG + zG * zG)
        if (!lastMagnitude.isNaN() && abs(magnitude - lastMagnitude) > config.jerkThresholdG) bucket.counts++
        lastMagnitude = magnitude
        if (!worn) bucket.offBody = true
        if (charging) bucket.charging = true
    }

    /** A stored heart rate sample; never a no-contact reading. */
    fun onHeartRate(epochMillis: Long, bpm: Int) {
        val bucket = openBucketAt(epochMillis)
        if (bucket != null) {
            bucket.heartRates += bpm.toDouble()
            return
        }
        // Late: it joins the closed minute, which [revisions] hands out again.
        val closed = closedRecently[minuteOf(epochMillis)] ?: return
        closed.heartRates += bpm.toDouble()
        revised += closed.row.epochMillis
    }

    /** The off-body sensor's verdict. Sticky until the next one. */
    fun onWorn(epochMillis: Long, worn: Boolean) {
        this.worn = worn
        if (!worn) openBucketAt(epochMillis)?.offBody = true
    }

    /** The charger plugged or unplugged. Sticky until the next one. */
    fun onCharging(epochMillis: Long, charging: Boolean) {
        this.charging = charging
        if (charging) openBucketAt(epochMillis)?.charging = true
    }

    /** The screen became interactive: the wearer looked at the watch, so the minute is awake. */
    fun onScreenOn(epochMillis: Long) {
        openBucketAt(epochMillis)?.screenOn = true
    }

    /** The heart rate sensor reported no contact, an unreliable reading or 0 bpm. */
    fun onHeartRateContact(epochMillis: Long, contact: Boolean) {
        if (contact) return
        val bucket = openBucketAt(epochMillis)
        if (bucket != null) {
            bucket.heartRateNoContact = true
            return
        }
        val closed = closedRecently[minuteOf(epochMillis)] ?: return
        if (closed.row.flags and WearLinkProtocol.FLAG_HR_NO_CONTACT != 0) return
        closed.row = closed.row.copy(flags = closed.row.flags or WearLinkProtocol.FLAG_HR_NO_CONTACT)
        revised += closed.row.epochMillis
    }

    /** Closes and returns every minute old enough as of [nowEpochMillis], oldest first. */
    fun close(nowEpochMillis: Long): List<WearLinkProtocol.SleepMinute> {
        val closed = ArrayList<WearLinkProtocol.SleepMinute>()
        val iterator = buckets.entries.iterator()
        while (iterator.hasNext()) {
            val (start, bucket) = iterator.next()
            if (nowEpochMillis - start < config.closeLagMillis) break
            val row = row(start, bucket)
            closed += row
            closedRecently[start] = Closed(row, bucket.heartRates)
            closedThrough = maxOf(closedThrough, start)
            iterator.remove()
        }
        while (closedRecently.isNotEmpty() && nowEpochMillis - closedRecently.firstKey() > config.lateHeartRateMillis) {
            revised -= closedRecently.pollFirstEntry().key
        }
        return closed
    }

    /**
     * The closed minutes a late heart rate reached since the last call, oldest first: the whole
     * row again, with the heart rate of every sample it now holds, so the stored row is replaced
     * by a fuller one, never an emptier one.
     */
    fun revisions(): List<WearLinkProtocol.SleepMinute> {
        val rows = revised.mapNotNull { start ->
            val closed = closedRecently[start] ?: return@mapNotNull null
            val hn = closed.heartRates.size
            val (heartMean, heartSd) = AccelerationMinuteFeatures.meanSd(closed.heartRates)
            val row = closed.row
            closed.row = row.copy(
                kind = kindOf(row.flags, hn),
                bpm = if (hn > 0) round(heartMean) else null,
                heartRateSd10 = if (hn > 0) round(heartSd * 10) else null,
                heartRateSamples = hn,
            )
            closed.row
        }
        revised.clear()
        return rows
    }

    private fun row(start: Long, bucket: Bucket): WearLinkProtocol.SleepMinute {
        val accel = bucket.features.close()
        val n = accel.sampleCount
        val countsNorm = if (n > 0) bucket.counts * AccelerationMinuteFeatures.NOMINAL_SAMPLES.toDouble() / n else 0.0
        val movement = if (countsNorm < config.minCounts) 0.0 else minOf(config.maxMovement, config.movementPerCount * countsNorm)

        val diffs = ArrayList<Double>(accel.angles.size)
        var previous = lastAngle
        for (angle in accel.angles) {
            if (previous != null) diffs += abs(angle - previous)
            previous = angle
        }
        if (accel.angles.isNotEmpty()) lastAngle = accel.angles.last()

        var flags = 0
        if (bucket.charging) flags = flags or WearLinkProtocol.FLAG_CHARGING
        if (bucket.offBody) flags = flags or WearLinkProtocol.FLAG_OFF_BODY
        if (bucket.screenOn) flags = flags or WearLinkProtocol.FLAG_SCREEN_ON
        if (bucket.heartRateNoContact) flags = flags or WearLinkProtocol.FLAG_HR_NO_CONTACT
        val heartRateRecording = isHeartRateRecording()
        if (heartRateRecording) flags = flags or WearLinkProtocol.FLAG_HR_RECORDING
        if (n < config.sparseSamples) flags = flags or WearLinkProtocol.FLAG_SPARSE

        val hn = bucket.heartRates.size
        val (heartMean, heartSd) = AccelerationMinuteFeatures.meanSd(bucket.heartRates)

        return WearLinkProtocol.SleepMinute(
            epochMillis = start,
            kind = kindOf(flags, hn),
            offsetSeconds = offsetSecondsAt(start),
            flags = flags,
            sampleCount = n,
            movement10 = round(movement * 10),
            bpm = if (hn > 0) round(heartMean) else null,
            heartRateSd10 = if (hn > 0) round(heartSd * 10) else null,
            heartRateSamples = hn,
            meanMilliG = IntArray(3) { round(accel.meanG[it] * 1000) },
            sdMilliG = IntArray(3) { round(accel.sdG[it] * 1000) },
            zAngleMin = accel.angles.minOrNull()?.let(::round),
            zAngleMax = accel.angles.maxOrNull()?.let(::round),
            zAngleDelta10 = if (diffs.isEmpty()) null else round(diffs.sum() / diffs.size * 10),
        )
    }

    /** Off the wrist, on the charger, or no pulse while recording: unmeasurable; else a screen wake-up is awake. */
    private fun kindOf(flags: Int, heartRateSamples: Int): WearLinkProtocol.MinuteKind = when {
        flags and (WearLinkProtocol.FLAG_CHARGING or WearLinkProtocol.FLAG_OFF_BODY) != 0 ||
            (flags and WearLinkProtocol.FLAG_HR_RECORDING != 0 && heartRateSamples == 0) -> WearLinkProtocol.MinuteKind.UNMEASURABLE
        flags and WearLinkProtocol.FLAG_SCREEN_ON != 0 -> WearLinkProtocol.MinuteKind.AWAKE
        else -> WearLinkProtocol.MinuteKind.RAW
    }

    private fun minuteOf(epochMillis: Long): Long = epochMillis - epochMillis % MINUTE_MILLIS

    /** The minute's bucket, opened on its first reading; null once the minute has closed. */
    private fun openBucketAt(epochMillis: Long): Bucket? {
        val start = minuteOf(epochMillis)
        if (start <= closedThrough) return null
        return buckets.getOrPut(start) {
            Bucket(start).also {
                it.charging = charging
                it.offBody = !worn
            }
        }
    }

    companion object {
        const val MINUTE_MILLIS = 60_000L

        /** `floor(x + 0.5)`, the one rounding the specification allows. */
        fun round(value: Double): Int = Math.round(value).toInt()
    }
}
