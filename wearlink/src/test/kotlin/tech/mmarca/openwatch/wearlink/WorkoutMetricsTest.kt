package tech.mmarca.openwatch.wearlink

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.healthcalculations.insights.HeartRateZones
import tech.mmarca.healthcalculations.insights.WorkoutEnergyResult
import tech.mmarca.healthcalculations.preferences.BiologicalSex
import tech.mmarca.healthcalculations.preferences.BodyProfile
import tech.mmarca.openwatch.wearlink.WorkoutMetrics.Beat

/**
 * What [WorkoutMetrics] hands health-calculations and takes back: the figures themselves are the
 * library's and tested there; here, that the workout's pauses, route and basis reach it.
 */
class WorkoutMetricsTest {

    private val start = 1_794_000_000_000L
    private val day: LocalDate = Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate()
    private val basis = HeartRateZones.Basis(maxBpm = 190, restingBpm = 50)

    /** Born 30 years before the workout's day, 70 kg, 175 cm. */
    private val man = BodyProfile(birthYear = day.year - 30, weightKg = 70.0, heightCm = 175.0, sex = BiologicalSex.MALE)

    private fun minutes(n: Int) = n * 60_000L

    /** [count] readings at [bpm], one a second from [from]. */
    private fun steady(bpm: Int, count: Int, from: Long = start) = List(count) { Beat(from + it * 1_000L, bpm) }

    private fun workout(
        minutes: Int,
        route: List<WearLinkWorkout.RoutePoint> = emptyList(),
        pauses: List<WearLinkWorkout.Pause> = emptyList(),
        exerciseType: Int = WorkoutSport.WALKING.exerciseType,
        distanceMetres: Int? = null,
    ) = WearLinkWorkout(start, start + minutes(minutes), exerciseType, null, distanceMetres, null, pauses, route)

    @Test
    fun `the summary counts the active readings only, the mean over their time`() {
        val pause = WearLinkWorkout.Pause(start + 30_000, start + 60_000)
        val beats = steady(100, 30) + steady(160, 30, start + 30_000) + steady(120, 30, start + 60_000)

        val summary = WorkoutMetrics.summarize(workout(2, pauses = listOf(pause)).copy(endMillis = start + 90_000), beats, basis, BodyProfile(), day)

        // 29 s at 100, the second across the pause at their mean of 110, 29 s at 120.
        assertEquals(110, summary.averageBpm)
        assertEquals("the pause's 160 counts for nothing", 120, summary.maxBpm)
        // Zones from 50, 92, 106, 134 and 176 bpm: 100 is zone 2, 120 zone 3; the pause and the last reading count no time.
        assertEquals(listOf(0L, 30L, 29L, 0L, 0L), summary.zoneSeconds)
        assertNull("no weight, no energy", summary.totalKcal)
    }

    @Test
    fun `without a basis there are no zones, no TRIMP and no VO2max`() {
        val summary = WorkoutMetrics.summarize(workout(10), steady(150, 601), zones = null, man, day)

        assertEquals(List(HeartRateZones.COUNT) { 0L }, summary.zoneSeconds)
        assertNull(summary.trimp)
        assertNull(summary.vo2Max)
        assertNotNull("the energy needs no basis", summary.activeKcal)
    }

    @Test
    fun `trimp of ten minutes at half the reserve, a man`() {
        val summary = WorkoutMetrics.summarize(workout(10), steady(120, 601), basis, man, day)

        // 10 min x 0.5 x 0.64 x e^(1.92 x 0.5) = 8.36 (Banister, through health-calculations' workoutTrimp).
        assertEquals(8.36, summary.trimp!!, 0.01)
    }

    @Test
    fun `the energy is health-calculations' estimate over the active time`() {
        val workout = workout(10, exerciseType = WorkoutSport.RUNNING.exerciseType)

        val estimate = WorkoutMetrics.energy(workout, steady(150, 601), man, day) as WorkoutEnergyResult.Estimate
        val summary = WorkoutMetrics.summarize(workout, steady(150, 601), basis, man, day)

        // Keytel at 150 bpm: 14.22 kcal a minute; Mifflin-St Jeor's rest: 1.145 kcal a minute.
        assertEquals(142.22, estimate.totalKcal, 0.01)
        assertEquals(130.77, estimate.activeKcal, 0.01)
        assertEquals(estimate.activeKcal, summary.activeKcal!!, 0.0)
        assertTrue(WorkoutMetrics.energy(workout, steady(150, 601), BodyProfile(), day) is WorkoutEnergyResult.MissingInputs)
    }

    @Test
    fun `a straight kilometre is one split, its time without the pause in it`() {
        // 0.0009 degrees of latitude every 30 s: about 100.1 m a step, 1000.8 m in ten.
        val route = List(11) { WearLinkWorkout.RoutePoint(start + it * 30_000L, it * 9_000, 0, null, null) }
        val pause = WearLinkWorkout.Pause(start + 60_000, start + 90_000)

        val splits = WorkoutMetrics.splits(workout(6, route))
        val paused = WorkoutMetrics.splits(workout(6, route, listOf(pause)))

        assertEquals(1, splits.size)
        assertEquals(0, splits[0].index)
        assertEquals(start, splits[0].startMillis)
        assertEquals(299L, splits[0].activeSeconds)
        assertEquals(269L, paused.single().activeSeconds)
    }

    @Test
    fun `the pace is the active time over the distance`() {
        val pause = WearLinkWorkout.Pause(start + minutes(5), start + minutes(10))

        val summary = WorkoutMetrics.summarize(workout(30, pauses = listOf(pause), distanceMetres = 5_000), emptyList(), null, BodyProfile(), day)

        assertEquals(300.0, summary.paceSecondsPerKm!!, 1e-9)
        assertNull(WorkoutMetrics.summarize(workout(30), emptyList(), null, BodyProfile(), day).paceSecondsPerKm)
    }

    @Test
    fun `vo2max from a steady run, and none from a sport its equations do not hold for`() {
        // 200 m a minute for ten minutes at 150 bpm, resting 50 and maximum 190: a reserve of 0.714,
        // so 0.2 x 200 / 0.714 + 3.5 = 59.5 ml/kg/min.
        val step = (200.0 / 111_195.0 * 1e7).toInt()
        val route = List(11) { WearLinkWorkout.RoutePoint(start + minutes(it), it * step, 0, null, null) }
        val beats = steady(150, 601)

        val run = WorkoutMetrics.summarize(workout(10, route, exerciseType = WorkoutSport.RUNNING.exerciseType), beats, basis, man, day)
        val volleyball = WorkoutMetrics.summarize(workout(10, route, exerciseType = WorkoutSport.VOLLEYBALL.exerciseType), beats, basis, man, day)

        assertEquals(59.5, run.vo2Max!!, 0.5)
        assertNull(volleyball.vo2Max)
    }

    @Test
    fun `the session less its pauses is the active time`() {
        val pauses = listOf(WearLinkWorkout.Pause(start + minutes(2), start + minutes(3)), WearLinkWorkout.Pause(start + minutes(5), start + minutes(6)))

        val windows = WorkoutMetrics.activeWindows(workout(10, pauses = pauses))

        assertEquals(
            listOf(0L to 2L, 3L to 5L, 6L to 10L),
            windows.map { (it.start.toEpochMilli() - start) / minutes(1) to (it.end.toEpochMilli() - start) / minutes(1) },
        )
    }

    @Test
    fun `the recovery is the fall from the peak before the stop after one and two minutes`() {
        val stop = start + minutes(10)
        val beats = steady(160, 10, stop - 9_000) + steady(130, 11, stop + 55_000) + steady(110, 11, stop + 115_000)

        assertEquals(WorkoutMetrics.Recovery(oneMinuteBpm = 30, twoMinutesBpm = 50, peakBpm = 160), WorkoutMetrics.recovery(beats, stop, man, 50, day))
    }

    @Test
    fun `a heart rate that did not fall after the stop is no recovery`() {
        val stop = start + minutes(10)
        val beats = steady(160, 10, stop - 9_000) + steady(165, 11, stop + 55_000) + steady(150, 11, stop + 115_000)

        assertEquals(WorkoutMetrics.Recovery(null, null, peakBpm = 160), WorkoutMetrics.recovery(beats, stop, man, 50, day))
    }

    @Test
    fun `the falls and the peak need no profile, only the verdict does`() {
        val stop = start + minutes(10)
        val beats = steady(160, 10, stop - 9_000) + steady(130, 11, stop + 55_000) + steady(110, 11, stop + 115_000)

        assertEquals(WorkoutMetrics.Recovery(oneMinuteBpm = 30, twoMinutesBpm = 50, peakBpm = 160), WorkoutMetrics.recovery(beats, stop))
    }
}
