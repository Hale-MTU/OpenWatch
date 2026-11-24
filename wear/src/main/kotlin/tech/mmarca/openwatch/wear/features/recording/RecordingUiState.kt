package tech.mmarca.openwatch.wear.features.recording

import androidx.annotation.StringRes

/** A workout as the workout screens draw it, running or finished. A null value means "no reading yet". */
data class RecordingUiState(
    val activityType: ActivityType = ActivityType.WALK,
    /** The sport's own name, when it is more precise than [activityType]'s. */
    @param:StringRes val title: Int? = null,
    val elapsedSeconds: Long = 0,
    val heartRateBpm: Int? = null,
    val distanceMeters: Int? = null,
    val steps: Int? = null,
    val elevationGainMeters: Int? = null,
    /** The speed now, for the running workout; null while paused or without a recent fix, which reads as a dash. */
    val speedMetersPerSecond: Double? = null,
    /** Distance over active time, for the summary. */
    val averageSpeedMetersPerSecond: Double? = null,
    val averageHeartRateBpm: Int? = null,
    val paused: Boolean = false,
    /** A route sport without a usable GPS fix yet. */
    val waitingForFix: Boolean = false,
)
