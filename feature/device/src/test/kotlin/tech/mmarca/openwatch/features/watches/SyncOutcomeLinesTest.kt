package tech.mmarca.openwatch.features.watches

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.features.device.R
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncResult
import tech.mmarca.openwatch.devices.core.sync.HealthSeries
import tech.mmarca.openwatch.devices.core.sync.SkipReason
import tech.mmarca.openwatch.devices.core.sync.SkippedSeries
import tech.mmarca.openwatch.devices.core.sync.SyncTally

class SyncOutcomeLinesTest {

    @Test
    fun `a Wear OS run says its counts, each series left out, and asks for every grant at once`() {
        val lines = DeviceSyncResult.Succeeded(
            written = mapOf(SyncTally.HEART_RATE_SAMPLES to 40, SyncTally.STEP_MINUTES to 0, SyncTally.SLEEP_NIGHTS to 1),
            skipped = setOf(
                SkippedSeries(HealthSeries.WALKING_ENERGY, SkipReason.NO_BODY_WEIGHT, setOf(ReadWeight)),
                SkippedSeries(HealthSeries.STEPS, SkipReason.PERMISSION, setOf(WriteSteps)),
            ),
        ).lines()

        assertEquals(listOf(SyncTally.HEART_RATE_SAMPLES to 40, SyncTally.SLEEP_NIGHTS to 1), lines.written)
        assertFalse(lines.nothingNew)
        assertEquals(emptyList<Int>(), lines.notes)
        // The grant lines first, the weight it could read last, as the card always said them.
        assertEquals(
            listOf(R.string.settings_watch_wearos_needs_steps_write, R.string.settings_watch_wearos_needs_weight_read),
            lines.grantSentences,
        )
        assertEquals(setOf(WriteSteps, ReadWeight), lines.grants)
    }

    @Test
    fun `a Garmin run names its wellness grants by label and says why a workout has no calories`() {
        val lines = DeviceSyncResult.Succeeded(
            written = mapOf(SyncTally.FILES to 3),
            skipped = setOf(
                SkippedSeries(HealthSeries.RECORDS, SkipReason.PERMISSION, setOf(WriteSleep)),
                SkippedSeries(HealthSeries.WORKOUT_ENERGY, SkipReason.NO_BODY_WEIGHT),
            ),
        ).lines()

        assertEquals(listOf(SyncTally.FILES to 3), lines.written)
        assertEquals(listOf(R.string.settings_watch_garmin_needs_weight), lines.notes)
        assertEquals(emptyList<Int>(), lines.grantSentences)
        assertEquals(setOf(WriteSleep), lines.unnamedPermissions)
        assertEquals(setOf(WriteSleep), lines.grants)
    }

    @Test
    fun `nothing new is said only when nothing was left out either`() {
        assertTrue(DeviceSyncResult.Succeeded(written = mapOf(SyncTally.FILES to 0)).lines().nothingNew)
        assertFalse(
            DeviceSyncResult.Succeeded(
                skipped = setOf(SkippedSeries(HealthSeries.WALKING_ENERGY, SkipReason.NO_BODY_WEIGHT)),
            ).lines().nothingNew,
        )
    }
}

private const val ReadWeight = "android.permission.health.READ_WEIGHT"
private const val WriteSteps = "android.permission.health.WRITE_STEPS"
private const val WriteSleep = "android.permission.health.WRITE_SLEEP"
