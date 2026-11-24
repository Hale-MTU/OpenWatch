package tech.mmarca.openwatch.wear

/** Navigation routes. */
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
}
