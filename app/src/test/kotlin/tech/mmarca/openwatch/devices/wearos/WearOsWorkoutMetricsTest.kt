package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.records.Vo2MaxRecord
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.healthcalculations.insights.HeartRateRecoveryQuality
import tech.mmarca.openwatch.wearlink.WorkoutMetrics

class WearOsWorkoutMetricsTest {

    private val start = Instant.parse("2026-11-09T19:35:00Z")
    private val workout = WearOsWorkout(start, start.plusSeconds(3600), 78, null, null, null, emptyList(), emptyList())
    private fun summary(vo2: Double? = null, recovery: WorkoutMetrics.Recovery? = null) = WorkoutMetrics.Summary(
        averageBpm = 120, maxBpm = 160, zoneSeconds = listOf(600L, 1200L, 1800L, 0L, 0L), trimp = 85.4,
        activeKcal = 450.0, totalKcal = 520.0, splits = emptyList(), paceSecondsPerKm = null, vo2Max = vo2, recovery = recovery,
    )

    @Test
    fun `only the vo2max is a record here, and only with its grant`() {
        val records = WearOsWorkoutMetrics.records(workout, summary(48.0), ZoneOffset.UTC, setOf(WearOsWorkoutMetrics.vo2Permission))
        assertTrue(records.single() is Vo2MaxRecord)
        assertTrue(WearOsWorkoutMetrics.records(workout, summary(48.0), ZoneOffset.UTC, emptySet()).isEmpty())
    }

    @Test
    fun `the notes name the figures`() {
        assertEquals(
            "Heart rate avg 120, max 160 bpm. Zones (min) 10/20/30/0/0. TRIMP 85. Recovery 1 min 25 bpm",
            WearOsWorkoutMetrics.notes(summary(recovery = WorkoutMetrics.Recovery(25, null, 170, HeartRateRecoveryQuality.CLEAN))),
        )
        assertEquals(
            "Heart rate avg 120, max 160 bpm. Zones (min) 10/20/30/0/0. TRIMP 85. Recovery 1 min 25 bpm (effort not near-maximal)",
            WearOsWorkoutMetrics.notes(summary(recovery = WorkoutMetrics.Recovery(25, null, 150, HeartRateRecoveryQuality.NOT_COMPARABLE))),
        )
    }
}
