package tech.mmarca.openwatch.wear

import java.time.Duration
import java.time.LocalDate
import tech.mmarca.openwatch.wearlink.WearLinkEntry

/** Everything the screens render. A null value means "no reading yet". */
data class WearUiState(
    /**
     * The metrics this watch can give ([MetricOffer.available]). Only these
     * are offered as tiles. Every metric until the sensors are read.
     */
    val availableMetrics: Set<MetricTile> = MetricTile.entries.toSet(),
    /** What the watch has to measure with; null until it is read. */
    val sensors: WatchSensors? = null,
    /** A metric without an entry renders as "no reading yet". */
    val metrics: Map<MetricTile, MetricUiState> = emptyMap(),
    val preferences: DisplayPreferences = DisplayPreferences(),
    /** Last night's minutes per stage, as synced from the phone. */
    val sleepStages: Map<SleepStage, Double> = emptyMap(),
    /** The entries made on this watch, by hand or by a spot measurement, oldest first. */
    val entries: List<WearLinkEntry> = emptyList(),
    /**
     * The dashboard's tiles the wearer chose, in their order
     * ([WearTilePreferences]), whether the watch offers them now or not.
     */
    val tiles: List<MetricTile> = MetricTile.DefaultTiles,
    /** The quick log's entries the wearer chose, in their order ([WearQuickLogPreferences]). */
    val quickLogItems: List<QuickLogItem> = QuickLogItem.Defaults,
) {
    /** The chosen tiles the dashboard shows: those of [tiles] the watch offers now, so one appears once it is on offer. */
    val shownTiles: List<MetricTile>
        get() = tiles.filter { it in availableMetrics }
}

/** One metric's readings, in the base unit documented on [MetricTile]. */
data class MetricUiState(
    /**
     * Today's total for a cumulative metric, the latest sample of the heart
     * rate, and last night's value of a vital the watch takes at night: null
     * when the night that ended today has none, however recent an older one.
     */
    val current: Double? = null,
    /** A daily goal. Only meaningful for a cumulative metric. */
    val goal: Double? = null,
    /**
     * Today, oldest first: hourly totals for a cumulative metric; for a
     * sampled one its samples, or the values of [line] where the watch read
     * it, and last night's for a night's vital.
     */
    val today: List<Double> = emptyList(),
    /**
     * The last days, oldest first, today last: daily totals, daily averages,
     * or each night's value on the day it ended. It starts at the first day
     * the watch has data of, so it may be shorter than a week, and a later
     * day without data is null: no day reads as a zero it did not record.
     */
    val week: List<Double?> = emptyList(),
    /**
     * The date of [week]'s last entry, the day the readings were made on,
     * which the week's labels count back from. It comes with the readings,
     * so a screen left open over midnight is labelled for the new day as
     * soon as it shows the new day's values.
     */
    val weekEnd: LocalDate? = null,
    /**
     * Today's lowest, mean and highest sample, where [today] is not the samples
     * themselves: the heart rate's line is five-minute means and the skin
     * temperature's quarter-hour means, which hide the lowest and the highest
     * reading. Null takes the three from [today].
     */
    val todayStats: SampleStats? = null,
    /**
     * Today's time in each heart rate zone, zone 1 first, as health-calculations
     * counts it. Empty for any other metric and without a basis for the zones.
     */
    val zoneTime: List<Duration> = emptyList(),
    /**
     * Today's line of a sampled metric, over time: runs of points, with a
     * break between two runs where the watch has no reading (off the wrist,
     * on the charger). Empty for a cumulative metric, which draws [today] as
     * bars.
     */
    val line: List<List<LinePoint>> = emptyList(),
)

/** One point of a metric's line: [at] is its time, from 0 at the line's first point to 1 at its last. */
data class LinePoint(val at: Float, val value: Double)

/**
 * A day's samples in three figures, in the metric's base unit. [average] is
 * null where the metric's headline is that figure already, as a night's
 * value is: it then has no row of its own.
 */
data class SampleStats(val low: Double, val average: Double?, val high: Double)

/**
 * How the screens show a reading. Readings stay metric; only their display
 * changes.
 */
data class DisplayPreferences(
    /** The wearer's choice in the watch's settings ([WatchPreferences.unitSystem]). */
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    /** The day's water goal, millilitres. */
    val waterGoalMl: Int = DEFAULT_WATER_GOAL_ML,
) {
    companion object {
        /** Two litres, a common goal for a day; there is no setting for it yet. */
        const val DEFAULT_WATER_GOAL_ML = 2_000

        /** A glass: what one tap on water adds, in the quick log and on its tile, millilitres. */
        const val WATER_SERVING_ML = 250.0
    }
}
