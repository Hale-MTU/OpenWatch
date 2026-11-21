package tech.mmarca.openwatch.wear

/** Navigation routes. */
object WearRoutes {
    const val DASHBOARD = "dashboard"
    const val METRIC_ARG = "metric"
    const val METRIC = "metric/{$METRIC_ARG}"
    fun metric(metric: MetricTile) = "metric/${metric.name}"
    const val STATUS = "status"
}
