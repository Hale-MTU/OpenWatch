package tech.mmarca.openwatch.devices.blestandard

/** One heart rate a strap streamed, stamped by the phone as it arrived: the measurement carries no time. */
public data class HeartRateSample(public val atMillis: Long, public val beatsPerMinute: Int)

/**
 * A run of a strap's samples, one clock minute's, as the family keeps it until Health Connect
 * has it, and its key: `hr_<first sample's millis>`. Stored, so the format is final:
 * `hr1;<zone offset seconds>;<millis>:<bpm>,<millis>:<bpm>...`, oldest first.
 */
public data class HeartRateSamples(
    public val samples: List<HeartRateSample>,
    /** The phone's offset from UTC when they arrived. */
    public val zoneOffsetSeconds: Int,
) {
    init {
        require(samples.isNotEmpty()) { "a run has a sample" }
    }

    public val key: String get() = "$KEY_PREFIX${samples.first().atMillis}"

    public fun encode(): String =
        "$VERSION;$zoneOffsetSeconds;" + samples.joinToString(",") { "${it.atMillis}:${it.beatsPerMinute}" }

    public companion object {
        /** The key every run of samples goes under, before its first sample's time. */
        public const val KEY_PREFIX: String = "hr_"
        private const val VERSION = "hr1"

        /** [payload] read back, or null for one this version does not know or that is damaged. */
        public fun decode(payload: String): HeartRateSamples? {
            val parts = payload.split(';')
            if (parts.size != 3 || parts[0] != VERSION) return null
            val offset = parts[1].toIntOrNull() ?: return null
            val samples = parts[2].split(',').map { pair ->
                val (at, bpm) = pair.split(':').takeIf { it.size == 2 } ?: return null
                HeartRateSample(at.toLongOrNull() ?: return null, bpm.toIntOrNull() ?: return null)
            }
            return samples.takeIf { it.isNotEmpty() }?.let { HeartRateSamples(it, offset) }
        }
    }
}

/**
 * Gathers a stream's samples into one run per clock minute: [add] hands back the finished run
 * when a sample of a later minute starts the next, and [drain] the one under way when the stream
 * ends.
 */
public class HeartRateMinutes(private val zoneOffsetSeconds: () -> Int) {
    private val current = mutableListOf<HeartRateSample>()

    public fun add(sample: HeartRateSample): HeartRateSamples? {
        val done = current.firstOrNull()
            ?.takeIf { it.atMillis / MINUTE_MILLIS != sample.atMillis / MINUTE_MILLIS }
            ?.let { drain() }
        current += sample
        return done
    }

    public fun drain(): HeartRateSamples? {
        if (current.isEmpty()) return null
        return HeartRateSamples(current.toList(), zoneOffsetSeconds()).also { current.clear() }
    }

    private companion object {
        const val MINUTE_MILLIS = 60_000L
    }
}
