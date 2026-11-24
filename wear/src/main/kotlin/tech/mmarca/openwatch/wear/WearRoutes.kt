package tech.mmarca.openwatch.wear

/**
 * Navigation routes. Each feature package declares its own destinations, a
 * `NavGraphBuilder` extension in its `…Navigation.kt` that `OpenWatchWearApp`
 * calls; the routes stay here, so one feature can open another's.
 */
object WearRoutes {
    const val DASHBOARD = "dashboard"
    const val METRIC_ARG = "metric"
    const val METRIC = "metric/{$METRIC_ARG}"
    fun metric(metric: MetricTile) = "metric/${metric.name}"

    /** The metric a [METRIC_ARG] names; null for a name this build has no metric for, and for none. */
    fun metricOf(argument: String?): MetricTile? = MetricTile.entries.firstOrNull { it.name == argument }
    const val STATUS = "status"

    /** The workout: the sport picker, the running workout, and the summary of a saved one. */
    const val WORKOUT = "workout"

    /** The quick log: water at a tap, and the ways to the other entries. */
    const val QUICK_LOG = "quick_log"
    const val LOG_WEIGHT = "log_weight"
    const val LOG_BLOOD_PRESSURE = "log_blood_pressure"

    /** The guided breathing minute, logged as a mindful session when it ends. */
    const val BREATHE = "breathe"

    /** The spot measurements this watch can take. */
    const val MEASURE = "measure"
    const val MEASUREMENT_ARG = "measurement"

    /** One spot measurement, `measure/HEART_RATE`, from the list or a metric's detail. */
    const val MEASUREMENT = "measure/{$MEASUREMENT_ARG}"
    fun measurement(kind: SpotKind) = "measure/${kind.name}"

    /** The measurement a [MEASUREMENT_ARG] names; null for a name this build has none for, and for none. */
    fun spotKindOf(argument: String?): SpotKind? = SpotKind.entries.firstOrNull { it.name == argument }
}
