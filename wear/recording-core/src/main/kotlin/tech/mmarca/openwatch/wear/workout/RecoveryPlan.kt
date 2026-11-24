package tech.mmarca.openwatch.wear.workout

import tech.mmarca.healthcalculations.insights.HeartRateZones

/** What a stop does about the heart rate recovery. */
enum class RecoveryAtStop {
    /** Nothing: no zones say the heart rate was up, or it is too low for a fall to mean anything. */
    NONE,

    /** The wearer was still before the stop: the falls are read from the readings already taken. */
    FROM_REST,

    /** The heart rate stays live for the two minutes after the stop, if the wearer agrees. */
    LIVE,
}

/**
 * A rest at the end of a workout and the heart rate's fall in it, as
 * `WorkoutMetrics.recovery` reads one: [startMillis] is when the arm came to
 * rest, [peakBpm] the peak just before it, and the falls [oneMinuteBpm] and
 * [twoMinutesBpm] once the readings reach that far (null before, and for a
 * reading that would mislead).
 */
data class RestRecovery(
    val startMillis: Long,
    val peakBpm: Int,
    val oneMinuteBpm: Int?,
    val twoMinutesBpm: Int?,
)

/**
 * Chooses how a stop treats the recovery. A rest of a minute or more before
 * the stop, with the heart rate high when it began, already holds the
 * recovery: [RecoveryAtStop.FROM_REST]. Otherwise a heart rate still in the
 * light zone or above is worth asking about: [RecoveryAtStop.LIVE] when the
 * wearer agrees. Without zones (no maximum from the phone) nothing says the
 * heart rate was up: [RecoveryAtStop.NONE], and nothing is asked. The zones
 * are health-calculations' ([HeartRateZones]), on the basis the phone sent. Pure.
 */
object RecoveryPlan {

    /** The lowest zone, 0 being very light, from which the end of an exercise is worth a recovery: light. */
    const val MIN_ZONE = 1

    fun atStop(rest: RestRecovery?, bpmNow: Int?, zones: HeartRateZones.Basis?): RecoveryAtStop = when {
        rest != null && rest.oneMinuteBpm != null && worthMeasuring(rest.peakBpm, zones) -> RecoveryAtStop.FROM_REST
        bpmNow != null && worthMeasuring(bpmNow, zones) -> RecoveryAtStop.LIVE
        else -> RecoveryAtStop.NONE
    }

    private fun worthMeasuring(bpm: Int, zones: HeartRateZones.Basis?): Boolean =
        zones != null && (HeartRateZones.zoneOf(bpm.toLong(), zones.boundsBpm) ?: -1) >= MIN_ZONE
}
