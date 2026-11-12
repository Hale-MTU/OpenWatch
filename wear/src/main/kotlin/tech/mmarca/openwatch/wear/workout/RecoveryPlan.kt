package tech.mmarca.openwatch.wear.workout

import tech.mmarca.openwatch.wear.features.metric.ZoneBasis
import tech.mmarca.openwatch.wearlink.HeartRateZones

/** What a stop does about the heart rate recovery. */
enum class RecoveryAtStop {
    /** Nothing: the heart rate is too low for a fall to mean anything. */
    NONE,

    /** The wearer was still before the stop; the fall is read from the readings already taken. */
    FROM_REST,

    /** Keep the heart rate live for two minutes. */
    LIVE,
}

/**
 * Chooses how a stop treats the recovery. A rest of a minute or more before the stop, with the heart
 * rate high when it began, already holds the recovery: [RecoveryAtStop.FROM_REST]. Otherwise a heart rate
 * still in the light zone or above is worth asking about: [RecoveryAtStop.LIVE] when the wearer agrees.
 */
internal object RecoveryPlan {

    /** The lowest zone (0 is very light) whose end of exercise a recovery is worth measuring from. */
    const val MIN_ZONE = 1

    fun atStop(rest: RestRecovery?, bpmNow: Int?, zones: ZoneBasis): RecoveryAtStop = when {
        rest != null && rest.oneMinuteBpm != null && worthMeasuring(rest.endBpm, zones) -> RecoveryAtStop.FROM_REST
        bpmNow != null && worthMeasuring(bpmNow, zones) -> RecoveryAtStop.LIVE
        else -> RecoveryAtStop.NONE
    }

    private fun worthMeasuring(bpm: Int, zones: ZoneBasis): Boolean =
        (HeartRateZones.zoneOf(bpm.toDouble(), zones) ?: -1) >= MIN_ZONE
}
