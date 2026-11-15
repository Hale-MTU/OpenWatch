package tech.mmarca.openwatch.wear

/** Everything the screens render. A null value means "no reading yet". */
data class WearUiState(
    /** The metrics the dashboard may show. */
    val availableMetrics: Set<MetricTile> = MetricTile.entries.toSet(),
    /** A metric without an entry renders as "no reading yet". */
    val metrics: Map<MetricTile, MetricUiState> = emptyMap(),
    val preferences: DisplayPreferences = DisplayPreferences(),
)

/** One metric's readings, in the base unit documented on [MetricTile]. */
data class MetricUiState(
    /** Today's total for a cumulative metric, the latest sample otherwise. */
    val current: Double? = null,
    /** A daily goal. Only meaningful for a cumulative metric. */
    val goal: Double? = null,
    /** Today, oldest first: hourly totals for a cumulative metric, samples otherwise. */
    val today: List<Double> = emptyList(),
    /** The last days, oldest first, today last: daily totals or daily averages. */
    val week: List<Double> = emptyList(),
)

/**
 * Display settings the watch does not own: the phone app is where they are
 * chosen, and a later sync writes them here. Readings stay metric; only
 * their display changes.
 */
data class DisplayPreferences(
    val unitSystem: UnitSystem = UnitSystem.METRIC,
)
