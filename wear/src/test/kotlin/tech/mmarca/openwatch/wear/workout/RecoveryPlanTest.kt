package tech.mmarca.openwatch.wear.workout

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.mmarca.openwatch.wearlink.HeartRateZones

class RecoveryPlanTest {

    private val zones = HeartRateZones.Basis(maxBpm = 190, restingBpm = 60)

    private fun rest(endBpm: Int, one: Int? = 25) = RestRecovery(startMillis = 0, endBpm = endBpm, oneMinuteBpm = one, twoMinutesBpm = null)

    @Test fun `a rest from a high heart rate holds the recovery already`() {
        assertEquals(RecoveryAtStop.FROM_REST, RecoveryPlan.atStop(rest(160), bpmNow = 130, zones = zones))
    }

    @Test fun `without a rest a high heart rate asks`() {
        assertEquals(RecoveryAtStop.LIVE, RecoveryPlan.atStop(null, bpmNow = 150, zones = zones))
    }

    @Test fun `a rest without its first minute falls back to asking`() {
        assertEquals(RecoveryAtStop.LIVE, RecoveryPlan.atStop(rest(160, one = null), bpmNow = 140, zones = zones))
    }

    @Test fun `a low heart rate neither asks nor measures`() {
        assertEquals(RecoveryAtStop.NONE, RecoveryPlan.atStop(null, bpmNow = 75, zones = zones))
        assertEquals(RecoveryAtStop.NONE, RecoveryPlan.atStop(rest(80), bpmNow = 70, zones = zones))
    }

    @Test fun `without zones from the phone nothing says the heart rate was up`() {
        assertEquals(RecoveryAtStop.NONE, RecoveryPlan.atStop(rest(160), bpmNow = 150, zones = null))
    }

    @Test fun `a rest from a low heart rate with a high one now still asks`() {
        assertEquals(RecoveryAtStop.LIVE, RecoveryPlan.atStop(rest(80), bpmNow = 150, zones = zones))
    }

    @Test fun `no heart rate means nothing to ask about`() {
        assertEquals(RecoveryAtStop.NONE, RecoveryPlan.atStop(null, bpmNow = null, zones = zones))
    }
}
