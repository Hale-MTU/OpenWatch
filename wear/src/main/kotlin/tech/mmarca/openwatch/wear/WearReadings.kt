package tech.mmarca.openwatch.wear

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt
import tech.mmarca.healthcalculations.insights.AscentCredit
import tech.mmarca.healthcalculations.insights.HeartRateZones
import tech.mmarca.healthcalculations.model.HeartRateSample as ZoneSample
import tech.mmarca.healthcalculations.stats.medianOrNull
import tech.mmarca.healthcalculations.stats.timeBucketedAverageOrNull
import tech.mmarca.openwatch.wearlink.WearLinkEntry
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.AscentMinute
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.HeartRateSample
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.HrvWindow
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.RespiratoryWindow
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.SkinMinute
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.Spo2Measurement
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.StepMinute

/**
 * A night as the watch counts its vitals: from noon to noon, named by the
 * date it ends on, so the hours either side of midnight are one night and
 * the night a wearer wakes from is today's. [startMillis] is in it,
 * [endMillis] is not.
 */
internal data class NightWindow(val startMillis: Long, val endMillis: Long)

/** The rows of one night ([NightWindow]) as the store holds them, each series oldest first. */
internal data class NightRows(
    val hrv: List<HrvWindow> = emptyList(),
    val spo2: List<Spo2Measurement> = emptyList(),
    val skin: List<SkinMinute> = emptyList(),
    val respiratory: List<RespiratoryWindow> = emptyList(),
)

/**
 * One vital of one night, in its tile's base unit: the night's [value], the
 * [line] its chart draws over the night, and the lowest and highest reading.
 */
internal data class NightValue(val value: Double, val line: List<List<LinePoint>>, val low: Double, val high: Double)

/**
 * One local day of what the watch recorded, cut down to what the screens
 * show: the day from midnight to midnight, and the night that ended on it.
 * A day that is over does not change, so it is made once and kept; only
 * today is read again.
 */
internal data class DayReadings(
    val date: LocalDate,
    /** Steps per hour of the day, 24 values. */
    val stepsByHour: List<Double>,
    /** Metres of height that count, per hour, 24 values. See [AscentCredit]. */
    val ascentByHour: List<Double>,
    /** The day's heart rate as its chart draws it: the mean of each quarter hour, broken where the watch stored none. */
    val heartRateLine: List<List<LinePoint>>,
    /** The mean of the day's samples; null without one. */
    val heartRateMean: Double?,
    /** The day's lowest and highest sample; null without one. */
    val heartRateRange: IntRange?,
    val latestHeartRate: HeartRateSample?,
    val hasSteps: Boolean,
    val hasAscent: Boolean,
    /** The vitals of the night that ended on the day; a vital the night has no reading of is left out. */
    val night: Map<MetricTile, NightValue> = emptyMap(),
    /** The time in each heart rate zone, zone 1 first; empty for a day read without a basis for the zones. */
    val heartZoneTime: List<Duration> = emptyList(),
)

/** The Today tile's figures. */
internal data class TodayTotals(val steps: Int?, val heartRate: Int?)

/** Turns the watch's own rows into the values the dashboard and the detail screens read. */
internal object WearReadings {

    private const val HOURS = 24

    /** The tiles a day's [DayReadings.night] fills: the vitals the watch takes at night. */
    val NIGHT_METRICS = MetricTile.entries.filter { it.period == MetricPeriod.NIGHT }
    private const val DECIMETRES_PER_METRE = 10.0
    private const val MINUTE_MILLIS = 60_000L
    private const val QUARTER_HOUR_MILLIS = 15 * MINUTE_MILLIS

    /**
     * How far apart two of a night's readings may be and still be joined. An
     * HRV window comes at most every half hour and only while the arm is
     * still, so an hour between two is an ordinary night; two hours is time
     * awake or off the wrist.
     */
    private const val NIGHT_READING_BREAK_MILLIS = 120 * MINUTE_MILLIS

    /**
     * [points], each a time and a value, as a line over time: every point at
     * its share of the way from the first to the last, in runs. A new run
     * starts where two neighbours are more than [breakAfterMillis] apart, so
     * the chart shows a gap there instead of a line the watch never measured.
     */
    fun line(points: List<Pair<Long, Double>>, breakAfterMillis: Long): List<List<LinePoint>> {
        val ordered = points.sortedBy { it.first }
        val first = ordered.firstOrNull()?.first ?: return emptyList()
        val span = (ordered.last().first - first).toDouble()
        val runs = mutableListOf<MutableList<LinePoint>>()
        var previous = first
        for ((time, value) in ordered) {
            if (runs.isEmpty() || time - previous > breakAfterMillis) runs += mutableListOf<LinePoint>()
            // A single moment has no span: its one point sits in the middle.
            runs.last() += LinePoint(at = if (span > 0.0) ((time - first) / span).toFloat() else 0.5f, value = value)
            previous = time
        }
        return runs
    }

    /**
     * The day's heart rate over time: the mean of each quarter hour, the
     * buckets the heart rate tile draws ([TileDay.meanPerBucket]), broken at
     * a quarter hour without a sample.
     */
    private fun heartRateLine(samples: List<HeartRateSample>, zone: ZoneId): List<List<LinePoint>> {
        val latest = samples.maxOfOrNull { it.epochMillis } ?: return emptyList()
        val day = TileDay.of(latest, zone)
        val means = day.meanPerBucket(samples).mapIndexedNotNull { bucket, mean ->
            mean?.let { day.startMillis + bucket * TileDay.BUCKET_MILLIS to it.toDouble() }
        }
        return line(means, breakAfterMillis = TileDay.BUCKET_MILLIS)
    }

    /**
     * The night that ends on [date]: from noon the day before to noon of
     * [date], by the wall clock, so it is 23 or 25 hours long when the clocks
     * change in it. health-calculations names a night the same way, by the
     * date it ends on (`nightRmssdByDate`, `sleepNightWindow`).
     */
    fun nightWindow(date: LocalDate, zone: ZoneId): NightWindow = NightWindow(
        startMillis = date.minusDays(1).atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli(),
        endMillis = date.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli(),
    )

    /**
     * A night's vitals from its [rows], one value a night:
     * - HRV: the mean of the positive readings as health-calculations takes a
     *   night's (`nightRmssdByDate`), the mean of the means of each minute.
     *   The watch keeps no sleep session, so the night's window stands in for
     *   the session the library cuts by.
     * - Skin temperature: the median of the night's minutes, as the phone
     *   takes a night's for its baseline. The chart draws quarter-hour means;
     *   the lowest and highest are those of the minutes.
     * - Blood oxygen and breathing rate: the mean of the night's readings.
     *
     * Each one's line runs over the night's time ([line]): the readings where
     * they were taken, joined unless [NIGHT_READING_BREAK_MILLIS] apart; the
     * skin temperature's quarter hours, broken at a missing one.
     */
    fun night(rows: NightRows): Map<MetricTile, NightValue> = buildMap {
        val hrv = rows.hrv.filter { it.rmssdMillis > 0.0 }
        hrv.timeBucketedAverageOrNull(time = { Instant.ofEpochMilli(it.epochMillis) }, value = { it.rmssdMillis })?.let { mean ->
            put(MetricTile.HRV, nightValue(mean, hrv.map { it.epochMillis to it.rmssdMillis }))
        }
        if (rows.spo2.isNotEmpty()) {
            val percents = rows.spo2.map { it.epochMillis to it.percent.toDouble() }
            put(MetricTile.BLOOD_OXYGEN, nightValue(percents.map { it.second }.average(), percents))
        }
        val skin = rows.skin.map { it.skinCelsius }
        skin.medianOrNull()?.let { median ->
            val byQuarterHour = rows.skin.groupBy { it.epochMillis / QUARTER_HOUR_MILLIS }
                .map { (quarter, minutes) -> quarter * QUARTER_HOUR_MILLIS to minutes.map { it.skinCelsius }.average() }
            put(
                MetricTile.SKIN_TEMPERATURE,
                NightValue(median, line(byQuarterHour, breakAfterMillis = QUARTER_HOUR_MILLIS), low = skin.min(), high = skin.max()),
            )
        }
        if (rows.respiratory.isNotEmpty()) {
            val breaths = rows.respiratory.map { it.epochMillis to it.breathsPerMinute }
            put(MetricTile.RESPIRATORY_RATE, nightValue(breaths.map { it.second }.average(), breaths))
        }
    }

    /** A night whose chart draws its [readings], each a time and a value, themselves. */
    private fun nightValue(value: Double, readings: List<Pair<Long, Double>>): NightValue = NightValue(
        value = value,
        line = line(readings, breakAfterMillis = NIGHT_READING_BREAK_MILLIS),
        low = readings.minOf { it.second },
        high = readings.maxOf { it.second },
    )

    /**
     * The rows of [date] only, and of the night that ended on it ([night],
     * [nightWindow]); the caller reads them from the store. With a
     * [zoneBasis] the day's time in the heart rate zones is counted too.
     */
    fun day(
        date: LocalDate,
        zone: ZoneId,
        heartRate: List<HeartRateSample>,
        steps: List<StepMinute>,
        ascent: List<AscentMinute>,
        night: NightRows = NightRows(),
        zoneBasis: HeartRateZones.Basis? = null,
    ): DayReadings {
        val stepsAt = steps.associate { it.epochMillis to it.steps }
        val stepsByHour = DoubleArray(HOURS)
        for (minute in steps) stepsByHour[hourOf(minute.epochMillis, zone)] += minute.steps.toDouble()

        val ascentByHour = DoubleArray(HOURS)
        for (minute in ascent) {
            val walked = (stepsAt[minute.epochMillis - MINUTE_MILLIS] ?: 0) + (stepsAt[minute.epochMillis] ?: 0)
            ascentByHour[hourOf(minute.epochMillis, zone)] +=
                AscentCredit.creditedMetres(minute.decimetres / DECIMETRES_PER_METRE, walked)
        }

        return DayReadings(
            date = date,
            stepsByHour = stepsByHour.toList(),
            ascentByHour = ascentByHour.toList(),
            heartRateLine = heartRateLine(heartRate, zone),
            heartRateMean = heartRate.takeIf { it.isNotEmpty() }?.map { it.bpm }?.average(),
            heartRateRange = heartRate.takeIf { it.isNotEmpty() }?.let { samples -> samples.minOf { it.bpm }..samples.maxOf { it.bpm } },
            latestHeartRate = heartRate.maxByOrNull { it.epochMillis },
            hasSteps = steps.isNotEmpty(),
            hasAscent = ascent.isNotEmpty(),
            night = night(night),
            heartZoneTime = zoneBasis?.let { timeInZones(heartRate, it) }.orEmpty(),
        )
    }

    /**
     * The time [samples] spent in each zone of [basis], zone 1 first, as
     * health-calculations counts it ([HeartRateZones.timeInZones]): a reading
     * counts until the next one, for no time when the next is more than
     * [HeartRateZones.MaxSampleGap] away, and for no zone when it is below
     * zone 1. The phone's zones come from the same rule.
     */
    fun timeInZones(samples: List<HeartRateSample>, basis: HeartRateZones.Basis): List<Duration> =
        HeartRateZones.timeInZones(
            samples = samples.map { ZoneSample(Instant.ofEpochMilli(it.epochMillis), it.bpm.toLong(), source = "") },
            lowerBpm = basis.boundsBpm,
        )

    /**
     * The tiles the watch's own recording can fill, and the [entries] made on
     * it ([putEntries]). [days] are the last seven, oldest first, today last.
     * A tile with no row in any of them is left out, so the screen says "no
     * reading yet" instead of showing zero.
     */
    fun metrics(
        days: List<DayReadings>,
        now: Instant,
        zone: ZoneId,
        entries: List<WearLinkEntry> = emptyList(),
        waterGoalMl: Double = DisplayPreferences.DEFAULT_WATER_GOAL_ML.toDouble(),
    ): Map<MetricTile, MetricUiState> {
        val today = days.lastOrNull() ?: return emptyMap()
        val thisHour = hourOf(now.toEpochMilli(), zone)
        return buildMap {
            if (days.any { it.hasSteps }) {
                put(MetricTile.STEPS, cumulative(days, thisHour, { it.hasSteps }) { it.stepsByHour })
            }
            if (days.any { it.hasAscent }) {
                put(MetricTile.ELEVATION, cumulative(days, thisHour, { it.hasAscent }) { it.ascentByHour })
                put(
                    MetricTile.FLOORS,
                    cumulative(days, thisHour, { it.hasAscent }) { day -> day.ascentByHour.map { it / AscentCredit.METRES_PER_FLOOR } },
                )
            }
            for (metric in NIGHT_METRICS) {
                if (days.none { metric in it.night }) continue
                // Last night's value is the one shown as now. An older night has its bar, and is not passed off as today's.
                val lastNight = today.night[metric]
                put(
                    metric,
                    MetricUiState(
                        current = lastNight?.value,
                        today = lastNight?.line.orEmpty().flatten().map { it.value },
                        line = lastNight?.line.orEmpty(),
                        week = days.dropWhile { it.night[metric] == null }.map { day -> day.night[metric]?.value },
                        // The headline is the night's value already: the lowest and highest reading frame it.
                        todayStats = lastNight?.let { SampleStats(low = it.low, average = null, high = it.high) },
                    ),
                )
            }
            val latest = days.lastNotNullOfOrNull { it.latestHeartRate }
            if (latest != null) {
                put(
                    MetricTile.HEART_RATE,
                    MetricUiState(
                        current = latest.bpm.toDouble(),
                        today = today.heartRateLine.flatten().map { it.value },
                        line = today.heartRateLine,
                        // The chart labels its bars as the last days, today last: a day without a sample keeps its place.
                        week = days.dropWhile { it.heartRateMean == null }.map { it.heartRateMean },
                        todayStats = sampleStats(today),
                        zoneTime = today.heartZoneTime,
                    ),
                )
            }
            putEntries(days, entries, thisHour, zone, waterGoalMl)
        }.mapValues { (_, state) -> state.copy(weekEnd = today.date) }
    }

    /**
     * The entries made on the watch, folded into the tiles they feed: the
     * water drunk into the water tile, with its goal, as a daily total by the
     * hour; the weights into the weight tile, the newest as the value now
     * however old, today's as its chart and each day's last as the week. A
     * heart rate or an HRV measured on the spot today is the value now only
     * where the watch's own recording has none.
     */
    private fun MutableMap<MetricTile, MetricUiState>.putEntries(
        days: List<DayReadings>,
        entries: List<WearLinkEntry>,
        thisHour: Int,
        zone: ZoneId,
        waterGoalMl: Double,
    ) {
        if (entries.isEmpty()) return
        val entryDays = EntryDays.of(entries, days.map { it.date }, zone)
        val today = entryDays.last()
        if (entryDays.any { it.hasWater }) {
            put(
                MetricTile.HYDRATION,
                MetricUiState(
                    current = today.waterByHour.sum(),
                    goal = waterGoalMl,
                    today = today.waterByHour.take(thisHour + 1),
                    week = entryDays.dropWhile { !it.hasWater }.map { day -> day.waterByHour.sum().takeIf { day.hasWater } },
                ),
            )
        }
        EntryDays.latest(entries, WearLinkEntry.Type.WEIGHT)?.let { latest ->
            put(
                MetricTile.WEIGHT,
                MetricUiState(
                    current = latest.value,
                    today = today.weights.map { it.value },
                    line = line(today.weights.map { it.epochMillis to it.value }, breakAfterMillis = Long.MAX_VALUE),
                    week = entryDays.dropWhile { it.weights.isEmpty() }.map { day -> day.weights.lastOrNull()?.value },
                ),
            )
        }
        for ((metric, type) in SPOT_ENTRIES) {
            val logged = EntryDays.latestOn(entries, type, today.date, zone) ?: continue
            val state = this[metric] ?: MetricUiState()
            if (state.current == null) put(metric, state.copy(current = logged.value))
        }
    }

    /** The tiles a spot measurement's entry can stand in for, with its type. */
    private val SPOT_ENTRIES = listOf(
        MetricTile.HEART_RATE to WearLinkEntry.Type.HEART_RATE,
        MetricTile.HRV to WearLinkEntry.Type.HRV,
    )

    /** What the Today tile shows: the day's steps and the latest heart rate, each null when there is none. */
    fun totals(day: DayReadings): TodayTotals = TodayTotals(
        steps = day.stepsByHour.sum().roundToInt().takeIf { day.hasSteps },
        heartRate = day.latestHeartRate?.bpm,
    )

    /** The day's lowest, mean and highest heart rate sample, the range the heart rate tile shows; null on a day without one. */
    private fun sampleStats(day: DayReadings): SampleStats? {
        val range = day.heartRateRange ?: return null
        val mean = day.heartRateMean ?: return null
        return SampleStats(low = range.first.toDouble(), average = mean, high = range.last.toDouble())
    }

    /** [recorded] says whether a day has a row of the metric at all: a day without one is no data, not a total of zero. */
    private fun cumulative(
        days: List<DayReadings>,
        thisHour: Int,
        recorded: (DayReadings) -> Boolean,
        hours: (DayReadings) -> List<Double>,
    ): MetricUiState {
        val today = hours(days.last())
        return MetricUiState(
            current = today.sum(),
            today = today.take(thisHour + 1),
            week = days.dropWhile { !recorded(it) }.map { day -> hours(day).sum().takeIf { recorded(day) } },
        )
    }

    private fun hourOf(epochMillis: Long, zone: ZoneId): Int =
        Instant.ofEpochMilli(epochMillis).atZone(zone).hour

    private fun <T, R : Any> List<T>.lastNotNullOfOrNull(selector: (T) -> R?): R? =
        asReversed().firstNotNullOfOrNull(selector)
}
