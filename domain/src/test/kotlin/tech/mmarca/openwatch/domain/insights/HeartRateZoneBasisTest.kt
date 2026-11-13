package tech.mmarca.openwatch.domain.insights

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openwatch.domain.preferences.BiologicalSex
import tech.mmarca.openwatch.domain.preferences.BodyProfile

class HeartRateZoneBasisTest {

    private val today = LocalDate.of(2026, 11, 9)

    @Test
    fun `an entered maximum wins over the age`() {
        val basis = HeartRateZoneBasis.of(BodyProfile(birthYear = 1996, maxHeartRateBpm = 201, restingHeartRateBpm = 52), today)
        assertEquals(201, basis.maxBpm)
        assertEquals(52, basis.restingBpm)
    }

    @Test
    fun `the age predicts the maximum, by sex`() {
        assertEquals(187, HeartRateZoneBasis.of(BodyProfile(birthYear = 1996), today).maxBpm)
        assertEquals(180, HeartRateZoneBasis.of(BodyProfile(birthYear = 1996, sex = BiologicalSex.FEMALE), today).maxBpm)
    }

    @Test
    fun `an empty profile gives nothing`() {
        val basis = HeartRateZoneBasis.of(BodyProfile(), today)
        assertNull(basis.maxBpm)
        assertNull(basis.restingBpm)
    }

    @Test
    fun `a real observed maximum wins over the age, a brisk walk does not`() {
        val profile = BodyProfile(birthYear = 1996)
        assertEquals(196, HeartRateZoneBasis.of(profile, today, observedMaxBpm = 196, nightRestingBpm = 50).maxBpm)
        assertEquals(187, HeartRateZoneBasis.of(profile, today, observedMaxBpm = 140, nightRestingBpm = 50).maxBpm)
        // Without a resting rate an observed maximum cannot be judged.
        assertEquals(187, HeartRateZoneBasis.of(profile, today, observedMaxBpm = 196).maxBpm)
    }

    @Test
    fun `the resting rate is the entered one only, the night's just judges the maximum`() {
        val basis = HeartRateZoneBasis.of(BodyProfile(birthYear = 1996), today, nightRestingBpm = 50)
        assertNull(basis.restingBpm)
    }

    @Test
    fun `the observed maximum must clear 150 and resting plus 60, as everywhere in the app`() {
        val profile = BodyProfile(birthYear = 1996)
        // Resting 90: the bar is max(150, 150) = 150, and 150 clears it.
        assertEquals(150, HeartRateZoneBasis.of(profile, today, observedMaxBpm = 150, nightRestingBpm = 90).maxBpm)
        // Resting 91: the bar is 151, so Tanaka's 208 - 0.7 x 30 = 187 stands.
        assertEquals(187, HeartRateZoneBasis.of(profile, today, observedMaxBpm = 150, nightRestingBpm = 91).maxBpm)
    }
}
