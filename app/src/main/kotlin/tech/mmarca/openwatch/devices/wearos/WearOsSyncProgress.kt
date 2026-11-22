package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncPhase
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncProgress
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncResult
import tech.mmarca.openwatch.devices.core.sync.HealthSeries
import tech.mmarca.openwatch.devices.core.sync.ProgressUnit
import tech.mmarca.openwatch.devices.core.sync.SkipReason
import tech.mmarca.openwatch.devices.core.sync.SkippedSeries
import tech.mmarca.openwatch.devices.core.sync.SyncTally

/** What one Wear OS sync has done so far: pages for the progress, counts and skips for the result. */
internal class WearOsSyncProgress(private val onProgress: ((DeviceSyncProgress) -> Unit)?) {
    var pages = 0
        private set

    /** Heart rate samples. */
    var written = 0
    var stepMinutes = 0
    var nights = 0

    val hasWritten: Boolean
        get() = written > 0 || stepMinutes > 0

    /** What the run left out, and why. */
    val skipped = mutableSetOf<SkippedSeries>()

    /** The grants the skipped series wait for. */
    val withheldPermissions: Set<String>
        get() = skipped.flatMapTo(mutableSetOf()) { it.permissions }

    /** The budget ended the run before every series. */
    var moreRemains = false

    /** [series] was left out for want of [permissions]. */
    fun withheld(series: HealthSeries, permissions: Set<String>) {
        skipped += SkippedSeries(series, SkipReason.PERMISSION, permissions)
    }

    /**
     * An estimate went without a body weight. [readWithheld]: Health Connect's was out of reach
     * for want of the read grant, so the card asks for it. One entry however many estimates
     * went without; true the first time.
     */
    fun noBodyWeight(readWithheld: Boolean): Boolean {
        val earlier = skipped.firstOrNull { it.reason == SkipReason.NO_BODY_WEIGHT }
        earlier?.let(skipped::remove)
        val read = if (readWithheld) setOf(WEIGHT_READ_PERMISSION) else emptySet()
        skipped += SkippedSeries(HealthSeries.WALKING_ENERGY, SkipReason.NO_BODY_WEIGHT, earlier?.permissions.orEmpty() + read)
        return earlier == null
    }

    fun page() {
        pages++
        onProgress?.invoke(DeviceSyncProgress(DeviceSyncPhase.DOWNLOADING, total = pages, done = pages - 1, unit = ProgressUnit.PAGES))
    }

    fun outcome(): DeviceSyncResult.Succeeded = DeviceSyncResult.Succeeded(
        written = mapOf(
            SyncTally.HEART_RATE_SAMPLES to written,
            SyncTally.STEP_MINUTES to stepMinutes,
            SyncTally.SLEEP_NIGHTS to nights,
        ),
        skipped = skipped.toSet(),
        moreRemains = moreRemains,
    )

    private companion object {
        val WEIGHT_READ_PERMISSION = HealthPermission.getReadPermission(WeightRecord::class)
    }
}
