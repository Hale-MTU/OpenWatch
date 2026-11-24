package tech.mmarca.openwatch.wear

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.healthcalculations.insights.HeartRateZones
import tech.mmarca.openwatch.wearlink.DaySummary
import tech.mmarca.openwatch.wearlink.WearLinkEntry
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.AscentMinute
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.HeartRateSample
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.HrvWindow
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.RespiratoryWindow
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.SkinMinute
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.Spo2Measurement
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.StepMinute

class WearReadingsTest {

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 2)
    private val now = Instant.parse("2026-11-02T10:30:00Z")

    /** Zones from 60, 102, 116, 144 and 186 bpm: the reserve between a resting 60 and a maximum of 200. */
    private val zoneBasis = HeartRateZones.Basis(maxBpm = 200, restingBpm = 60)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0, second: Int = 0): Long =
        date.atTime(hour, minute, second).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun day(
        date: LocalDate,
        heartRate: List<HeartRateSample> = emptyList(),
        steps: List<StepMinute> = emptyList(),
        ascent: List<AscentMinute> = emptyList(),
        zoneBasis: HeartRateZones.Basis? = null,
        night: NightRows = NightRows(),
    ) = WearReadings.day(date, zone, heartRate, steps, ascent, night, zoneBasis)

    private fun heartRateOf(samples: List<HeartRateSample>, zoneBasis: HeartRateZones.Basis? = null): MetricUiState =
        WearReadings.metrics(week(day(today, heartRate = samples, zoneBasis = zoneBasis)), now, zone).getValue(MetricTile.HEART_RATE)

    private fun seconds(vararg perZone: Long): List<Duration> = perZone.map(Duration::ofSeconds)

    /** Seven days, today last; [today] carries the rows, the earlier ones are empty unless given. */
    private fun week(todayReadings: DayReadings, earlier: Map<Int, DayReadings> = emptyMap()): List<DayReadings> =
        (6 downTo 1).map { back -> earlier[back] ?: day(today.minusDays(back.toLong())) } + todayReadings

    @Test
    fun stepsAddUpPerHourAndPerDay() {
        val readings = week(
            day(today, steps = listOf(StepMinute(at(today, 8, 0), 40), StepMinute(at(today, 8, 1), 60), StepMinute(at(today, 10, 5), 25))),
            earlier = mapOf(1 to day(today.minusDays(1), steps = listOf(StepMinute(at(today.minusDays(1), 12), 500)))),
        )

        val steps = WearReadings.metrics(readings, now, zone).getValue(MetricTile.STEPS)

        assertEquals(125.0, steps.current!!, 0.0)
        assertEquals(40.0 + 60.0, steps.today[8], 0.0)
        assertEquals(25.0, steps.today[10], 0.0)
        // The week starts at the first day with steps: the five days before it are not zeros.
        assertEquals(listOf(500.0, 125.0), steps.week)
    }

    @Test
    fun todayStopsAtTheCurrentHour() {
        val readings = week(day(today, steps = listOf(StepMinute(at(today, 3), 10))))

        val steps = WearReadings.metrics(readings, now, zone).getValue(MetricTile.STEPS)

        assertEquals(11, steps.today.size)
    }

    @Test
    fun aMetricWithNoRowInTheWeekIsLeftOut() {
        val metrics = WearReadings.metrics(week(day(today)), now, zone)

        assertTrue(metrics.isEmpty())
    }

    @Test
    fun aQuietTodayStillShowsZeroWhenAnEarlierDayHadSteps() {
        val readings = week(
            day(today),
            earlier = mapOf(2 to day(today.minusDays(2), steps = listOf(StepMinute(at(today.minusDays(2), 9), 300)))),
        )

        val steps = WearReadings.metrics(readings, now, zone).getValue(MetricTile.STEPS)

        assertEquals(0.0, steps.current!!, 0.0)
        // Yesterday and today recorded nothing: no data, which is not a day of zero steps.
        assertEquals(listOf(300.0, null, null), steps.week)
    }

    @Test
    fun heightCountsOnlyWithStepsAroundIt() {
        val climbed = AscentMinute(at(today, 9, 10), 60) // 6 m
        val walkedNothing = week(day(today, ascent = listOf(climbed)))
        val walked = week(
            day(today, ascent = listOf(climbed), steps = listOf(StepMinute(at(today, 9, 10), 20), StepMinute(at(today, 9, 9), 20))),
        )

        val without = WearReadings.metrics(walkedNothing, now, zone).getValue(MetricTile.ELEVATION)
        val with = WearReadings.metrics(walked, now, zone).getValue(MetricTile.ELEVATION)

        assertEquals(0.0, without.current!!, 0.0)
        assertEquals(6.0, with.current!!, 1e-9) // 40 steps carry 12 m, the 6 m climbed all count
    }

    @Test
    fun floorsAreCreditedMetresOverThreeMetresPerFloor() {
        val readings = week(
            day(today, ascent = listOf(AscentMinute(at(today, 9, 10), 90)), steps = listOf(StepMinute(at(today, 9, 10), 60))),
        )

        val floors = WearReadings.metrics(readings, now, zone).getValue(MetricTile.FLOORS)

        assertEquals(3.0, floors.current!!, 1e-9) // 9 m climbed, 60 steps carry 18 m
    }

    @Test
    fun heartRateShowsTheLatestSampleAndALineOfQuarterHourMeans() {
        val samples = listOf(
            HeartRateSample(at(today, 9, 0), 60),
            HeartRateSample(at(today, 9, 2), 70),
            HeartRateSample(at(today, 9, 20), 90),
        )

        val heartRate = WearReadings.metrics(week(day(today, heartRate = samples)), now, zone).getValue(MetricTile.HEART_RATE)

        assertEquals(90.0, heartRate.current!!, 0.0)
        assertEquals(listOf(65.0, 90.0), heartRate.today)
        assertEquals(listOf(listOf(LinePoint(0f, 65.0), LinePoint(1f, 90.0))), heartRate.line)
    }

    @Test
    fun theHeartRatesLineRunsOverTimeAndBreaksWhereTheWatchStoredNothing() {
        // Worn from 8:00 to 8:45, on the charger, worn again from 10:00: the line must not join 8:30 to 10:00.
        val samples = listOf(
            HeartRateSample(at(today, 8, 0), 60),
            HeartRateSample(at(today, 8, 15), 62),
            HeartRateSample(at(today, 8, 30), 64),
            HeartRateSample(at(today, 10, 0), 90),
            HeartRateSample(at(today, 10, 15), 92),
        )

        val line = heartRateOf(samples).line

        assertEquals(2, line.size)
        assertEquals(listOf(60.0, 62.0, 64.0), line[0].map { it.value })
        assertEquals(listOf(90.0, 92.0), line[1].map { it.value })
        // 8:00 to 10:15 is 135 minutes: the points sit where their quarter hours fall in it, not at even steps.
        val expected = listOf(0f, 15 / 135f, 30 / 135f, 120 / 135f, 1f)
        line.flatten().map { it.at }.zip(expected).forEach { (at, wanted) -> assertEquals(wanted, at, 1e-6f) }
    }

    @Test
    fun aSingleReadingIsOnePointInTheMiddle() {
        assertEquals(listOf(listOf(LinePoint(0.5f, 70.0))), WearReadings.line(listOf(at(today, 9) to 70.0), breakAfterMillis = 1))
        assertTrue(WearReadings.line(emptyList(), breakAfterMillis = 1).isEmpty())
    }

    @Test
    fun heartRateLowAndHighAreTheSamplesNotTheLinesMeans() {
        // The line's points are 88 and the mean of 60, 62 and 95, a whole 72; the day's lowest reading is 60 and its highest 95.
        val samples = listOf(
            HeartRateSample(at(today, 9, 0), 88),
            HeartRateSample(at(today, 9, 1), 88),
            HeartRateSample(at(today, 9, 15), 60),
            HeartRateSample(at(today, 9, 16), 62),
            HeartRateSample(at(today, 9, 17), 95),
        )

        val heartRate = heartRateOf(samples)

        assertEquals(listOf(88.0, 72.0), heartRate.today)
        assertEquals(SampleStats(low = 60.0, average = (88 + 88 + 60 + 62 + 95) / 5.0, high = 95.0), heartRate.todayStats)
        assertEquals(95.0, heartRate.current!!, 0.0)
    }

    @Test
    fun heartRateHasNoStatsOnADayWithoutASample() {
        val readings = week(
            day(today, zoneBasis = zoneBasis),
            earlier = mapOf(1 to day(today.minusDays(1), heartRate = listOf(HeartRateSample(at(today.minusDays(1), 23, 50), 58)))),
        )

        val heartRate = WearReadings.metrics(readings, now, zone).getValue(MetricTile.HEART_RATE)

        assertNull(heartRate.todayStats)
        assertEquals(seconds(0, 0, 0, 0, 0), heartRate.zoneTime)
    }

    @Test
    fun timeInTheZonesCountsEachReadingUntilTheNext() {
        val samples = listOf(
            HeartRateSample(at(today, 9, 0, 0), 100), // zone 1 for 60 s
            HeartRateSample(at(today, 9, 1, 0), 150), // zone 4 for 10 s
            HeartRateSample(at(today, 9, 1, 10), 190), // zone 5 for 20 s
            HeartRateSample(at(today, 9, 1, 30), 120), // the last reading counts for no time
        )

        assertEquals(seconds(60, 0, 0, 10, 20), heartRateOf(samples, zoneBasis).zoneTime)
    }

    @Test
    fun aReadingBelowZoneOneCountsForNoZone() {
        // Under the resting rate the zones start at: an hour asleep at 50 bpm, then two minutes in zone 2.
        val asleep = (0..360).map { HeartRateSample(at(today, 3, 0) + it * 10_000L, 50) }
        val awake = listOf(HeartRateSample(at(today, 4, 0, 10), 110), HeartRateSample(at(today, 4, 2, 10), 110))

        val zoneTime = heartRateOf(asleep + awake, zoneBasis).zoneTime

        assertEquals(seconds(0, 120, 0, 0, 0), zoneTime)
    }

    @Test
    fun aGapLongerThanFiveMinutesCountsForNoTime() {
        val samples = listOf(
            HeartRateSample(at(today, 9, 0), 150), // zone 4 for 5 min: the longest spacing that counts
            HeartRateSample(at(today, 9, 5), 150), // the watch was off for 15 min: no time
            HeartRateSample(at(today, 9, 20), 190), // zone 5 for 30 s
            HeartRateSample(at(today, 9, 20, 30), 190),
        )

        assertEquals(seconds(0, 0, 0, 300, 30), heartRateOf(samples, zoneBasis).zoneTime)
    }

    @Test
    fun aWorkoutsOneASecondCountsLikeTheDaysGridInTheMeanButEveryReadingInTheZones() {
        // An hour below the zones at 55 every ten seconds, then ten minutes of a workout at 150 every second.
        val rest = (0 until 360).map { HeartRateSample(at(today, 8, 0) + it * 10_000L, 55) }
        val workout = (0 until 600).map { HeartRateSample(at(today, 9, 0) + it * 1_000L, 150) }

        val heartRate = heartRateOf(rest + workout, zoneBasis)

        // 360 samples at 55 and the workout's 60 grid slots at 150: 68.6, not the 114 of every sample.
        assertEquals((360 * 55 + 60 * 150.0) / 420, heartRate.todayStats!!.average!!, 1e-9)
        assertEquals(150.0, heartRate.todayStats!!.high, 0.0)
        assertEquals(seconds(0, 0, 0, 599, 0), heartRate.zoneTime)
    }

    @Test
    fun withoutAZoneBasisNoZoneTimeIsCounted() {
        val samples = listOf(HeartRateSample(at(today, 9, 0), 150), HeartRateSample(at(today, 9, 1), 150))

        val heartRate = heartRateOf(samples, zoneBasis = null)

        assertTrue(heartRate.zoneTime.isEmpty())
        assertEquals(SampleStats(low = 150.0, average = 150.0, high = 150.0), heartRate.todayStats)
    }

    @Test
    fun theZonesAreTodaysOnly() {
        val yesterday = today.minusDays(1)
        val readings = week(
            day(today, heartRate = listOf(HeartRateSample(at(today, 8, 0), 110), HeartRateSample(at(today, 8, 1), 110)), zoneBasis = zoneBasis),
            earlier = mapOf(
                1 to day(
                    yesterday,
                    heartRate = listOf(HeartRateSample(at(yesterday, 8, 0), 190), HeartRateSample(at(yesterday, 8, 4), 190)),
                    zoneBasis = zoneBasis,
                ),
            ),
        )

        assertEquals(seconds(0, 60, 0, 0, 0), WearReadings.metrics(readings, now, zone).getValue(MetricTile.HEART_RATE).zoneTime)
    }

    @Test
    fun heartRateWeekRunsFromTheFirstDayWithSamplesAndKeepsADayWithoutOneAsNoData() {
        val readings = week(
            day(today, heartRate = listOf(HeartRateSample(at(today, 8), 80))),
            earlier = mapOf(3 to day(today.minusDays(3), heartRate = listOf(HeartRateSample(at(today.minusDays(3), 8), 60), HeartRateSample(at(today.minusDays(3), 9), 70)))),
        )

        val heartRate = WearReadings.metrics(readings, now, zone).getValue(MetricTile.HEART_RATE)

        // Four days: three days ago, two without a sample, today. The chart labels the last four days.
        assertEquals(listOf(65.0, null, null, 80.0), heartRate.week)
    }

    @Test
    fun heartRateKeepsTheLastSampleWhenTodayHasNone() {
        val readings = week(
            day(today),
            earlier = mapOf(1 to day(today.minusDays(1), heartRate = listOf(HeartRateSample(at(today.minusDays(1), 23, 50), 58)))),
        )

        val heartRate = WearReadings.metrics(readings, now, zone).getValue(MetricTile.HEART_RATE)

        assertEquals(58.0, heartRate.current!!, 0.0)
        assertTrue(heartRate.today.isEmpty())
    }

    @Test
    fun noDaysGiveNoTiles() {
        assertTrue(WearReadings.metrics(emptyList(), now, zone).isEmpty())
        assertNull(WearReadings.metrics(week(day(today)), now, zone)[MetricTile.HEART_RATE])
        assertFalse(WearReadings.metrics(week(day(today)), now, zone).containsKey(MetricTile.STEPS))
    }

    @Test
    fun everyMetricsWeekEndsOnTheDayItWasReadOn() {
        val readings = week(
            day(
                today,
                heartRate = listOf(HeartRateSample(at(today, 8), 80)),
                steps = listOf(StepMinute(at(today, 8), 40)),
                night = NightRows(spo2 = listOf(Spo2Measurement(at(today, 3), 97, null))),
            ),
        )

        val metrics = WearReadings.metrics(readings, now, zone)

        assertEquals(setOf(MetricTile.STEPS, MetricTile.HEART_RATE, MetricTile.BLOOD_OXYGEN), metrics.keys)
        assertTrue(metrics.values.all { it.weekEnd == today })
    }

    @Test
    fun theNightsVitalsAreTheTilesTakenAtNight() {
        assertEquals(
            setOf(MetricTile.HRV, MetricTile.BLOOD_OXYGEN, MetricTile.SKIN_TEMPERATURE, MetricTile.RESPIRATORY_RATE),
            WearReadings.NIGHT_METRICS.toSet(),
        )
        // The watch measures them itself: night says when, not where from.
        assertTrue(WearReadings.NIGHT_METRICS.all { it.source == MetricSource.SENSOR })
    }

    @Test
    fun aNightIsFromNoonToNoonAndNamedByTheDateItEndsOn() {
        val window = WearReadings.nightWindow(today, zone)

        assertEquals(at(today.minusDays(1), 12), window.startMillis)
        assertEquals(at(today, 12), window.endMillis)
    }

    @Test
    fun aNightTheClocksChangeInIsAnHourShorterOrLonger() {
        val berlin = ZoneId.of("Europe/Berlin")
        val hour = 3_600_000L

        val spring = WearReadings.nightWindow(LocalDate.of(2026, 3, 29), berlin)
        val autumn = WearReadings.nightWindow(LocalDate.of(2026, 10, 25), berlin)

        assertEquals(23 * hour, spring.endMillis - spring.startMillis)
        assertEquals(25 * hour, autumn.endMillis - autumn.startMillis)
        // Noon by the wall clock on both sides: 11:00 UTC in winter time, 10:00 UTC in summer time.
        assertEquals(Instant.parse("2026-03-28T11:00:00Z").toEpochMilli(), spring.startMillis)
        assertEquals(Instant.parse("2026-03-29T10:00:00Z").toEpochMilli(), spring.endMillis)
    }

    @Test
    fun aNightsHrvIsTheMeanOfItsMinutesMeansOverPositiveReadings() {
        val yesterday = today.minusDays(1)
        val rows = NightRows(
            hrv = listOf(
                // Two windows in one minute count as one minute: (40 + 60) / 2, then 80, and a failed window of 0.
                HrvWindow(at(yesterday, 23, 0, 0), 400, 80),
                HrvWindow(at(yesterday, 23, 0, 30), 600, 80),
                HrvWindow(at(today, 3, 0), 800, 80),
                HrvWindow(at(today, 4, 0), 0, 0),
            ),
        )

        val hrv = WearReadings.metrics(week(day(today, night = rows)), now, zone).getValue(MetricTile.HRV)

        assertEquals(65.0, hrv.current!!, 1e-9)
        assertEquals(listOf(40.0, 60.0, 80.0), hrv.today)
        assertEquals(SampleStats(low = 40.0, average = null, high = 80.0), hrv.todayStats)
        assertEquals(listOf(65.0), hrv.week)
    }

    @Test
    fun aNightsBloodOxygenAndBreathingRateAreTheMeanOfItsReadings() {
        val rows = NightRows(
            spo2 = listOf(Spo2Measurement(at(today, 1), 94, 52), Spo2Measurement(at(today, 2), 97, null), Spo2Measurement(at(today, 3), 97, 50)),
            respiratory = listOf(RespiratoryWindow(at(today, 1), 142, 2), RespiratoryWindow(at(today, 2), 150, 3)),
        )

        val metrics = WearReadings.metrics(week(day(today, night = rows)), now, zone)

        assertEquals(96.0, metrics.getValue(MetricTile.BLOOD_OXYGEN).current!!, 1e-9)
        assertEquals(SampleStats(low = 94.0, average = null, high = 97.0), metrics.getValue(MetricTile.BLOOD_OXYGEN).todayStats)
        assertEquals(14.6, metrics.getValue(MetricTile.RESPIRATORY_RATE).current!!, 1e-9)
        assertEquals(listOf(14.2, 15.0), metrics.getValue(MetricTile.RESPIRATORY_RATE).today)
    }

    @Test
    fun aNightsSkinTemperatureIsTheMedianOfItsMinutesWithLowAndHighFromTheMinutes() {
        // Two quarter hours: 34.00, 34.40 and 36.00 degrees, then 35.00 and 35.20. The warm minute moves a mean, not the median.
        val rows = NightRows(
            skin = listOf(
                SkinMinute(at(today, 2, 0), 3400, 2500),
                SkinMinute(at(today, 2, 1), 3440, 2500),
                SkinMinute(at(today, 2, 2), 3600, 2500),
                SkinMinute(at(today, 2, 15), 3500, 2500),
                SkinMinute(at(today, 2, 16), 3520, 2500),
            ),
        )

        val skin = WearReadings.metrics(week(day(today, night = rows)), now, zone).getValue(MetricTile.SKIN_TEMPERATURE)

        assertEquals(35.0, skin.current!!, 1e-9)
        assertEquals(2, skin.today.size)
        assertEquals((34.0 + 34.4 + 36.0) / 3, skin.today[0], 1e-9)
        assertEquals(35.1, skin.today[1], 1e-9)
        // The quarter hours' means run from 34.8 to 35.1; the minutes from 34.0 to 36.0.
        assertEquals(SampleStats(low = 34.0, average = null, high = 36.0), skin.todayStats)
    }

    @Test
    fun aNightsLineJoinsItsReadingsOverTimeAndBreaksOnlyAfterTwoHours() {
        val yesterday = today.minusDays(1)
        val rows = NightRows(
            hrv = listOf(
                // Windows 40 and 70 minutes apart are one stretch of sleep; three hours awake is a break.
                HrvWindow(at(yesterday, 23, 0), 400, 80),
                HrvWindow(at(yesterday, 23, 40), 420, 80),
                HrvWindow(at(today, 0, 50), 440, 80),
                HrvWindow(at(today, 4, 0), 500, 80),
                HrvWindow(at(today, 5, 0), 520, 80),
            ),
        )

        val line = WearReadings.metrics(week(day(today, night = rows)), now, zone).getValue(MetricTile.HRV).line

        assertEquals(listOf(listOf(40.0, 42.0, 44.0), listOf(50.0, 52.0)), line.map { run -> run.map { it.value } })
        // 23:00 to 5:00 is 360 minutes.
        assertEquals(40 / 360f, line[0][1].at, 1e-6f)
        assertEquals(300 / 360f, line[1][0].at, 1e-6f)
    }

    @Test
    fun theSkinTemperaturesLineBreaksAtAQuarterHourWithoutAMinute() {
        val rows = NightRows(
            skin = listOf(
                SkinMinute(at(today, 2, 0), 3500, 2500),
                SkinMinute(at(today, 2, 15), 3510, 2500),
                // 2:30 to 2:45 has no minute: the sensor was not held.
                SkinMinute(at(today, 2, 45), 3530, 2500),
                SkinMinute(at(today, 3, 0), 3540, 2500),
            ),
        )

        val line = WearReadings.metrics(week(day(today, night = rows)), now, zone).getValue(MetricTile.SKIN_TEMPERATURE).line

        assertEquals(listOf(2, 2), line.map { it.size })
        assertEquals(listOf(0f, 0.25f, 0.75f, 1f), line.flatten().map { it.at })
    }

    @Test
    fun eachNightIsOneBarOnTheDayItEndedAndTwoNightsDoNotMix() {
        val yesterday = today.minusDays(1)
        val readings = week(
            day(today, night = NightRows(hrv = listOf(HrvWindow(at(yesterday, 23, 30), 400, 80), HrvWindow(at(today, 5), 600, 80)))),
            earlier = mapOf(1 to day(yesterday, night = NightRows(hrv = listOf(HrvWindow(at(yesterday, 2), 300, 80))))),
        )

        val hrv = WearReadings.metrics(readings, now, zone).getValue(MetricTile.HRV)

        assertEquals(listOf(30.0, 50.0), hrv.week)
        assertEquals(50.0, hrv.current!!, 0.0)
    }

    @Test
    fun aNightMetricWithoutAReadingIsLeftOut() {
        val metrics = WearReadings.metrics(week(day(today)), now, zone)
        assertFalse(MetricTile.SKIN_TEMPERATURE in metrics)
        assertFalse(MetricTile.BLOOD_OXYGEN in metrics)
    }

    @Test
    fun anOlderNightKeepsItsBarAndIsNotShownAsNow() {
        val yesterday = today.minusDays(1)
        val readings = week(
            day(today),
            earlier = mapOf(1 to day(yesterday, night = NightRows(spo2 = listOf(Spo2Measurement(at(yesterday, 3), 97, null))))),
        )

        val spo2 = WearReadings.metrics(readings, now, zone).getValue(MetricTile.BLOOD_OXYGEN)

        assertNull(spo2.current)
        assertTrue(spo2.today.isEmpty())
        assertNull(spo2.todayStats)
        assertEquals(listOf(97.0, null), spo2.week)
    }

    private fun entry(date: LocalDate, hour: Int, type: WearLinkEntry.Type, value: Double, minute: Int = 0) =
        WearLinkEntry.of(at(date, hour, minute), type, value)

    @Test
    fun waterLoggedOnTheWatchIsTheDaysTotalByTheHourWithItsGoal() {
        val yesterday = today.minusDays(1)
        val entries = listOf(
            entry(yesterday, 9, WearLinkEntry.Type.WATER, 500.0),
            entry(today, 8, WearLinkEntry.Type.WATER, 250.0),
            entry(today, 10, WearLinkEntry.Type.WATER, 250.0),
            entry(today, 10, WearLinkEntry.Type.WATER, 250.0, minute = 20),
        )

        val water = WearReadings.metrics(week(day(today)), now, zone, entries, waterGoalMl = 2_000.0).getValue(MetricTile.HYDRATION)

        assertEquals(750.0, water.current!!, 0.0)
        assertEquals(2_000.0, water.goal!!, 0.0)
        // Midnight to the hour of 10:30, as the steps.
        assertEquals(11, water.today.size)
        assertEquals(500.0, water.today[10], 0.0)
        // From the first day with water; today last.
        assertEquals(listOf(500.0, 750.0), water.week)
        assertEquals(today, water.weekEnd)
    }

    @Test
    fun theNewestWeightIsTheValueNowHoweverOldAndEachDaysLastIsItsBar() {
        val entries = listOf(
            entry(today.minusDays(20), 7, WearLinkEntry.Type.WEIGHT, 74.0),
            entry(today.minusDays(2), 7, WearLinkEntry.Type.WEIGHT, 72.9),
            entry(today.minusDays(2), 21, WearLinkEntry.Type.WEIGHT, 73.2),
        )

        val weight = WearReadings.metrics(week(day(today)), now, zone, entries).getValue(MetricTile.WEIGHT)

        assertEquals(73.2, weight.current!!, 0.0)
        assertEquals(listOf(73.2, null, null), weight.week)
        assertTrue(weight.today.isEmpty())

        val old = WearReadings.metrics(week(day(today)), now, zone, entries.take(1)).getValue(MetricTile.WEIGHT)
        assertEquals(74.0, old.current!!, 0.0)
        assertTrue(old.week.isEmpty())
    }

    @Test
    fun aSpotMeasurementTodayStandsInOnlyWhereTheRecordingHasNoValue() {
        val measured = listOf(
            entry(today, 9, WearLinkEntry.Type.HEART_RATE, 64.0),
            entry(today, 9, WearLinkEntry.Type.HRV, 48.0),
        )

        val empty = WearReadings.metrics(week(day(today)), now, zone, measured)
        assertEquals(64.0, empty.getValue(MetricTile.HEART_RATE).current!!, 0.0)
        assertEquals(48.0, empty.getValue(MetricTile.HRV).current!!, 0.0)

        val recorded = WearReadings.metrics(week(day(today, heartRate = listOf(HeartRateSample(at(today, 10), 72)))), now, zone, measured)
        assertEquals(72.0, recorded.getValue(MetricTile.HEART_RATE).current!!, 0.0)

        // Yesterday's measurement is not today's value.
        val yesterday = listOf(entry(today.minusDays(1), 9, WearLinkEntry.Type.HEART_RATE, 64.0))
        assertFalse(MetricTile.HEART_RATE in WearReadings.metrics(week(day(today)), now, zone, yesterday))
    }

    @Test
    fun withoutEntriesNoLoggedTileIsShown() {
        val metrics = WearReadings.metrics(week(day(today)), now, zone, emptyList())
        assertFalse(MetricTile.HYDRATION in metrics)
        assertFalse(MetricTile.WEIGHT in metrics)
    }

    @Test
    fun thePhonesFiguresFillSleepRestingRateEnergyAndDistanceTodayOnly() {
        val yesterday = today.minusDays(1).toEpochDay()
        val phone = mapOf(
            yesterday to DaySummary(yesterday, sleepMinutes = 400, restingBpm = 55, activeKcal = 300, distanceMetres = 4000),
            today.toEpochDay() to DaySummary(today.toEpochDay(), sleepMinutes = 430, awakeMinutes = 20, remMinutes = 90, lightMinutes = 250, deepMinutes = 70),
        )
        val metrics = WearReadings.metrics(week(day(today)), now, zone, phone = phone)

        assertEquals(430.0, metrics.getValue(MetricTile.SLEEP).current!!, 0.0)
        assertEquals(listOf(400.0, 430.0), metrics.getValue(MetricTile.SLEEP).week)
        // Yesterday's resting rate and energy have their bar; they are not today's.
        assertNull(metrics.getValue(MetricTile.RESTING_HEART_RATE).current)
        assertEquals(listOf(55.0, null), metrics.getValue(MetricTile.RESTING_HEART_RATE).week)
        assertNull(metrics.getValue(MetricTile.ACTIVE_CALORIES).current)
        assertEquals(listOf(4000.0, null), metrics.getValue(MetricTile.DISTANCE).week)
        assertEquals(today, metrics.getValue(MetricTile.DISTANCE).weekEnd)
        assertEquals(
            mapOf(SleepStage.AWAKE to 20.0, SleepStage.REM to 90.0, SleepStage.LIGHT to 250.0, SleepStage.DEEP to 70.0),
            WearReadings.sleepStages(phone, today),
        )
        assertTrue(WearReadings.sleepStages(phone, today.plusDays(1)).isEmpty())
    }

    @Test
    fun withoutThePhonesFiguresItsTilesHaveNoReading() {
        val metrics = WearReadings.metrics(week(day(today)), now, zone)
        for (tile in listOf(MetricTile.SLEEP, MetricTile.RESTING_HEART_RATE, MetricTile.ACTIVE_CALORIES, MetricTile.DISTANCE)) {
            assertFalse(tile.name, tile in metrics)
        }
    }
}
