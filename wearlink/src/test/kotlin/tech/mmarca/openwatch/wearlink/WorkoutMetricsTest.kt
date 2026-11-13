package tech.mmarca.openwatch.wearlink

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openwatch.wearlink.WorkoutMetrics.Beat
import tech.mmarca.openwatch.domain.insights.WorkoutEnergyMethod
import tech.mmarca.openwatch.domain.insights.WorkoutEnergyResult
import tech.mmarca.openwatch.domain.preferences.BiologicalSex
import tech.mmarca.openwatch.domain.preferences.BodyProfile

class WorkoutMetricsTest {

    private val start = 1_794_000_000_000L
    private fun minutes(n: Int) = n * 60_000L
    private fun steady(bpm: Int, count: Int, from: Long = start) = List(count) { Beat(from + it * 1000L, bpm) }
    private fun workout(minutes: Int, route: List<WearLinkWorkout.RoutePoint> = emptyList(), pauses: List<WearLinkWorkout.Pause> = emptyList()) =
        WearLinkWorkout(start, start + minutes(minutes), 79, null, null, null, pauses, route)

    private val today = LocalDate.of(2026, 11, 12)
    private val man = BodyProfile(birthYear = 1996, weightKg = 70.0, heightCm = 175.0, sex = BiologicalSex.MALE)

    @Test
    fun `the mean heart rate weighs each pair of readings by the time between them`() {
        // 100 and 100 bpm 10 s apart, then 160 bpm 60 s later: (100 * 10 + 130 * 60) / 70
        // = 125.71, as OpenVitals' timeWeightedMeanHeartRate; a plain average would say 120.
        val beats = listOf(Beat(start, 100), Beat(start + 10_000, 100), Beat(start + 70_000, 160))
        assertEquals(8800.0 / 70, WorkoutMetrics.meanBpm(workout(2), beats)!!, 1e-9)
        // The last reading stands for no time.
        assertEquals(listOf(10_000L, 60_000L, 0L), WorkoutMetrics.spans(beats, workout(2)).map { it.second })
    }

    @Test
    fun `readings further apart than five minutes count for no time`() {
        val beats = listOf(Beat(start, 100), Beat(start + minutes(5), 120), Beat(start + minutes(11), 180))
        assertEquals(listOf(minutes(5), 0L, 0L), WorkoutMetrics.spans(beats, workout(12)).map { it.second })
        assertEquals(110.0, WorkoutMetrics.meanBpm(workout(12), beats)!!, 1e-9)
    }

    @Test
    fun `energy is the app's estimate, keytel less mifflin for a man at 120 bpm`() {
        // Walking 30 min at 120 bpm, a man of 30, 70 kg, 175 cm. Tanaka's maximum is
        // 187, so 120 lies in Keytel's 57 to 90 %. Keytel: (-55.0969 + 0.6309 * 120
        // + 0.1988 * 70 + 0.2017 * 30) / 4.184 = 9.6984 kcal/min, 290.95 in 30 min.
        // Mifflin-St Jeor: 10 * 70 + 6.25 * 175 - 5 * 30 + 5 = 1648.75 kcal/day, 34.35.
        val beats = steady(120, 1800)
        val estimate = WorkoutMetrics.energy(workout(30), beats, man, today) as WorkoutEnergyResult.Estimate
        assertEquals(WorkoutEnergyMethod.HEART_RATE, estimate.method)
        assertEquals(290.95, estimate.totalKcal, 0.01)
        assertEquals(256.60, estimate.activeKcal, 0.01)
    }

    @Test
    fun `without a sex keytel and mifflin take the mean of both, as OpenVitals does`() {
        // Keytel at 120 bpm, 70 kg, 30 years: men 40.5781, women 26.6408 kJ/min, mean
        // 33.6095 kJ/min = 8.0329 kcal/min, 240.99 in 30 min. Mifflin with the sex term
        // at its mean, -78: 700 + 1093.75 - 150 - 78 = 1565.75 kcal/day, 32.62 in 30 min.
        val profile = BodyProfile(birthYear = 1996, weightKg = 70.0, heightCm = 175.0)
        val estimate = WorkoutMetrics.energy(workout(30), steady(120, 1800), profile, today) as WorkoutEnergyResult.Estimate
        assertEquals(WorkoutEnergyMethod.HEART_RATE, estimate.method)
        assertEquals(240.99, estimate.totalKcal, 0.01)
        assertEquals(208.37, estimate.activeKcal, 0.01)
    }

    @Test
    fun `trimp counts each pair of readings at their mean, as OpenVitals does`() {
        // 100 and 140 bpm, 10 s apart: their mean 120 is half of a 50 to 190 reserve.
        // 10 s / 60 * 0.5 * 0.64 * e^(1.92 * 0.5) = 0.1393. The old rule held 100 bpm for the 10 s.
        val pair = listOf(Beat(start, 100), Beat(start + 10_000, 140))
        assertEquals(0.1393, WorkoutMetrics.banisterTrimp(workout(1), pair, 50, 190, BiologicalSex.MALE)!!, 0.0001)
        // Ten minutes at half the reserve: 10 * 0.5 * 0.64 * e^(0.96) = 8.357.
        assertEquals(8.357, WorkoutMetrics.banisterTrimp(workout(11), steady(120, 601), 50, 190, BiologicalSex.MALE)!!, 0.001)
    }

    @Test
    fun `trimp counts nothing across a gap or a pause`() {
        val far = listOf(Beat(start, 120), Beat(start + minutes(6), 120))
        assertEquals(0.0, WorkoutMetrics.banisterTrimp(workout(7), far, 50, 190, BiologicalSex.MALE)!!, 0.0)
        val paused = workout(1, pauses = listOf(WearLinkWorkout.Pause(start + 2_000, start + 4_000)))
        val across = listOf(Beat(start, 120), Beat(start + 5_000, 120))
        assertEquals(0.0, WorkoutMetrics.banisterTrimp(paused, across, 50, 190, BiologicalSex.MALE)!!, 0.0)
    }

    @Test
    fun `summary counts only active readings, a pause cuts`() {
        val pause = WearLinkWorkout.Pause(start + 30_000, start + 60_000)
        val beats = steady(100, 30) + steady(160, 30, start + 30_000) + steady(120, 30, start + 60_000)
        val summary = WorkoutMetrics.summarize(workout(2, pauses = listOf(pause)).copy(endMillis = start + 90_000), beats, HeartRateZones.Basis(190, 50), BodyProfile(), today, null)
        assertEquals(110, summary.averageBpm)
        assertEquals(120, summary.maxBpm)
        assertNull(summary.totalKcal)
    }

    @Test
    fun `the route distance is the app's one haversine, on a 6 371 000 m sphere`() {
        // 0.009 degrees of latitude: 0.009 * pi / 180 * 6 371 000 = 1000.754 m.
        val a = WearLinkWorkout.RoutePoint(start, 0, 0, null, null)
        val b = WearLinkWorkout.RoutePoint(start + 30_000, 90_000, 0, null, null)
        assertEquals(1000.754, WorkoutMetrics.metresBetween(a, b), 0.001)
    }

    @Test
    fun `a straight kilometre is one split`() {
        // 0.009 degrees of latitude is about 1000.8 m.
        val route = List(11) { WearLinkWorkout.RoutePoint(start + it * 30_000L, it * 9_000, 0, null, null) }
        val splits = WorkoutMetrics.splits(workout(6, route))
        assertEquals(1, splits.size)
        assertEquals(299L, splits[0].activeSeconds)
    }

    @Test
    fun `vo2max from a steady run`() {
        // 200 m/min for 10 minutes at 150 bpm, rest 50, max 190: reserve 0.714,
        // VO2 = 3.5 + 0.2 * 200 = 43.5, VO2max = 40 / 0.714 + 3.5 = 59.5
        val metresPerFix = 200.0
        val step = (metresPerFix / 111_195.0 * 1e7).toInt()
        val route = List(11) { WearLinkWorkout.RoutePoint(start + minutes(it), it * step, 0, null, null) }
        val beats = steady(150, 600)
        val vo2 = WorkoutMetrics.vo2Max(workout(10, route), beats, HeartRateZones.Basis(190, 50), WorkoutSport.RUNNING)
        assertNotNull(vo2)
        assertEquals(59.5, vo2!!, 0.5)
        assertNull(WorkoutMetrics.vo2Max(workout(10, route), beats, HeartRateZones.Basis(190, 50), WorkoutSport.VOLLEYBALL))
    }

    @Test
    fun `recovery is the fall after one and two minutes`() {
        val stop = start + minutes(10)
        val beats = steady(160, 10, stop - 9_000) + steady(130, 11, stop + 55_000) + steady(110, 11, stop + 115_000)
        assertEquals(30 to 50, WorkoutMetrics.recovery(beats, stop))
    }

    @Test
    fun `acsm zones on the reserve`() {
        val basis = HeartRateZones.Basis(190, 50)
        assertEquals(1, HeartRateZones.zoneOf(92.0, basis))
        assertEquals(4, HeartRateZones.zoneOf(176.0, basis))
    }
}
