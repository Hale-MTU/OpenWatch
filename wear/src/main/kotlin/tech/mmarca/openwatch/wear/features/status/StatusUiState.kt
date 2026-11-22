package tech.mmarca.openwatch.wear.features.status

import tech.mmarca.openwatch.wear.WatchPreferences
import tech.mmarca.openwatch.wear.WearLinkUiState

/** What the status screen reads from the store, the grants and the system at one moment. */
internal data class StatusReadings(
    val heartRateGranted: Boolean,
    val bluetoothGranted: Boolean,
    val stepsGranted: Boolean,
    val hasStepCounter: Boolean,
    /** The newest heart rate stored, null for none. */
    val latestBpm: Int?,
    val heartRateSamples: Long,
    val sleepMinutes: Long,
    val stepMinutes: Long,
    /** Samsung's diagnostics consent; null on a watch without the setting. */
    val samsungDiagnostics: Boolean?,
    /** Whether the watch has the Samsung Health Platform, which adds its settings. */
    val samsungPlatform: Boolean,
    /** The raw PPG log of a debuggable build, started by a long press on the header. */
    val ppgLogAvailable: Boolean,
)

/** Everything the status screen renders. A null part is not read yet. */
internal data class StatusUiState(
    val readings: StatusReadings? = null,
    val link: WearLinkUiState = WearLinkUiState(),
    val settings: WatchPreferences? = null,
) {
    /** A grant the recorders or the link need is missing: the screen offers to ask for it. */
    val needsGrant: Boolean
        get() = readings != null &&
            (!readings.heartRateGranted || !readings.bluetoothGranted || (readings.hasStepCounter && !readings.stepsGranted))
}
