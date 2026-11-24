package tech.mmarca.openwatch.devices.garmin

import java.time.Instant

/**
 * Garmin's epoch: 1989-12-31T00:00:00Z. Directory entries use seconds
 * since it; a wire value of 0 is the "no date" sentinel.
 */
public object GarminTime {

    public const val GARMIN_EPOCH_SECONDS: Long = 631065600L

    /** A Garmin device timestamp (seconds since the Garmin epoch) as a UTC [Instant]. */
    public fun toInstant(garminTimestamp: Long): Instant =
        Instant.ofEpochSecond(garminTimestamp + GARMIN_EPOCH_SECONDS)

    /** An instant as a Garmin timestamp, for telling the watch the time. */
    public fun fromInstant(time: Instant): Long = time.epochSecond - GARMIN_EPOCH_SECONDS
}
