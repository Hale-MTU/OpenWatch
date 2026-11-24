package tech.mmarca.openwatch.wear.features.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.healthcalculations.insights.HeartRateRecoveryQuality
import tech.mmarca.openwatch.wear.R

/** The line under a recovery's falls on the summary: a word where the reading is less than clean. */
class RecoveryVerdictTest {

    @Test
    fun `a clean reading and one without data say nothing, the others say what they are`() {
        assertNull(recoveryVerdict(HeartRateRecoveryQuality.CLEAN))
        assertNull(recoveryVerdict(HeartRateRecoveryQuality.NO_DATA))
        assertEquals(R.string.recovery_approximate, recoveryVerdict(HeartRateRecoveryQuality.APPROXIMATE))
        assertEquals(R.string.recovery_not_comparable, recoveryVerdict(HeartRateRecoveryQuality.NOT_COMPARABLE))
        assertEquals(R.string.recovery_invalid, recoveryVerdict(HeartRateRecoveryQuality.INVALID))
    }
}
