package tech.mmarca.openwatch.wear

import java.time.Duration

/** Everything the screens render. A null value means "no reading yet". */
data class WearUiState(
    /**
     * The metrics this watch has a sensor for. Only these are offered as
     * tiles.
     */
    val availableMetrics: Set<MetricTile> = MetricTile.entries.toSet(),
    /** A metric without an entry renders as "no reading yet". */
    val metrics: Map<MetricTile, MetricUiState> = emptyMap(),
    val preferences: DisplayPreferences = DisplayPreferences(),
    /** Last night's minutes per stage, as synced from the phone. */
    val sleepStages: Map<SleepStage, Double> = emptyMap(),
)

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
    /** Today, oldest first: hourly totals for a cumulative metric, samples otherwise; last night's for a night's vital. */
    val today: List<Double> = emptyList(),
    /**
     * The last days, oldest first, today last: daily totals, daily averages,
     * or each night's value on the day it ended. It starts at the first day
     * the watch has data of, so it may be shorter than a week, and a later
     * day without data is null: no day reads as a zero it did not record.
     */
    val week: List<Double?> = emptyList(),
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
)

/**
 * A day's samples in three figures, in the metric's base unit. [average] is
 * null where the metric's headline is that figure already, as a night's
 * value is: it then has no row of its own.
 */
data class SampleStats(val low: Double, val average: Double?, val high: Double)

/**
 * Display settings the watch does not own: the phone app is where they are
 * chosen, and a later sync writes them here. Readings stay metric; only
 * their display changes.
 */
data class DisplayPreferences(
    val unitSystem: UnitSystem = UnitSystem.METRIC,
)
