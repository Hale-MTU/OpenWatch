package tech.mmarca.openwatch.domain.model

import java.time.Instant

/** What a watch that does not stage sleep said about one minute. */
enum class SleepMinuteKind {
    /** A feature row: heart rate and movement, left for the phone to classify. */
    RAW,

    /** The watch was sure the wearer was awake. */
    AWAKE,

    /** Not worn, or no signal. */
    UNMEASURABLE,
}

/** The estimator's input for one minute. Only movement and heart rate are used. */
data class SleepMinute(
    val time: Instant,
    val kind: SleepMinuteKind,
    /** Movement count for the minute; zero when still. Ignored unless [kind] is RAW. */
    val movement: Float = 0f,
    /** Beats per minute, or null when the row carried none. */
    val heartRate: Float? = null,
)
