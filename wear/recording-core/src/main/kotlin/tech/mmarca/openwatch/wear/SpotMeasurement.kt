package tech.mmarca.openwatch.wear

import tech.mmarca.healthcalculations.signal.HrvRmssd
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

/** What a running spot measurement ([SpotKind]) reports, and how it ends. */
sealed interface SpotEvent {
    /** A value to show while it runs: the pulse now, or the pressure now. */
    data class Live(val value: Double) : SpotEvent

    /** The sensor gives nothing usable yet: still locking on, or ([offBody]) not on a wrist. */
    data class Waiting(val offBody: Boolean) : SpotEvent

    /** The reading, in the kind's unit; [secondary] goes with it, the altitude of a pressure. The last event. */
    data class Result(val value: Double, val secondary: Double? = null) : SpotEvent

    /** No reading, and why. The last event. */
    data class Failed(val failure: SpotFailure) : SpotEvent
}

/** Why a spot measurement gave no reading. */
enum class SpotFailure {
    /** The watch has no sensor for it. */
    NOT_SUPPORTED,

    /** The heart rate grant is not in. */
    PERMISSION_DENIED,

    /** The sensor said the watch is not on a wrist. */
    OFF_BODY,

    /** The time ran out before the readings gave a value. */
    NO_SIGNAL,

    /** The sensor could not be started. */
    FAILED,
}

/** When a spot measurement has its reading, and what the reading is. Pure. */
object SpotRules {

    /** Readings a heart rate needs before it settles. */
    const val HEART_RATE_READINGS = 8

    /** The newest readings the settled pulse is the median of. */
    private const val SETTLED_OVER = 5

    /**
     * Clean beats a minute of HRV must rest on: half a minute at a resting
     * 60 bpm, a third of what a night's five-minute window needs.
     */
    const val HRV_MIN_CLEAN_BEATS = 30

    /** Pressure readings the reading averages, one a second. */
    const val PRESSURE_READINGS = 5

    /**
     * The settled pulse from [readings], oldest first: once
     * [HEART_RATE_READINGS] of them are a heart's, the median of the newest
     * five, so a stray reading while the sensor locks on does not move it.
     * Null until then.
     */
    fun settledHeartRate(readings: List<Int>): Double? {
        val valid = readings.filter { it in WearLinkProtocol.MIN_BPM..WearLinkProtocol.MAX_BPM }
        if (valid.size < HEART_RATE_READINGS) return null
        return valid.takeLast(SETTLED_OVER).sorted()[SETTLED_OVER / 2].toDouble()
    }

    /**
     * The RMSSD in milliseconds of the beats at [beatMillis], ascending, as
     * health-calculations takes a night window's ([HrvRmssd]), on at least
     * [HRV_MIN_CLEAN_BEATS] clean beats. Null when too few are clean.
     */
    fun rmssd(beatMillis: List<Double>): Double? =
        HrvRmssd.of(beatMillis.toDoubleArray(), minCleanBeats = HRV_MIN_CLEAN_BEATS)?.rmssdMillis

    /** The pulse two successive beats stand for; null for an interval no resting heart beats at. */
    fun beatBpm(previousMillis: Double, beatMillis: Double): Double? {
        val interval = beatMillis - previousMillis
        return if (interval in HrvRmssd.MIN_INTERVAL_MILLIS..HrvRmssd.MAX_INTERVAL_MILLIS) MILLIS_PER_MINUTE / interval else null
    }

    /**
     * The air pressure of [hectopascals], the mean of the readings, and the
     * altitude it stands for at standard sea-level pressure
     * ([AscentMinuteCounter.altitudeMetres]). Null for fewer than
     * [PRESSURE_READINGS].
     */
    fun airPressure(hectopascals: List<Float>): AirPressure? {
        if (hectopascals.size < PRESSURE_READINGS) return null
        val mean = hectopascals.map { it.toDouble() }.average()
        return AirPressure(mean, AscentMinuteCounter.altitudeMetres(mean))
    }

    /** A reading of the barometer: the pressure in hPa and its altitude in metres. */
    data class AirPressure(val hectopascals: Double, val altitudeMetres: Double)

    private const val MILLIS_PER_MINUTE = 60_000.0
}
