package tech.mmarca.openwatch.wear

import tech.mmarca.openwatch.wear.features.measure.Measurement

/** Navigation routes. */
object WearRoutes {
    const val DASHBOARD = "dashboard"
    const val METRIC_ARG = "metric"
    const val METRIC = "metric/{$METRIC_ARG}"
    fun metric(metric: MetricTile) = "metric/${metric.name}"
    const val STATUS = "status"
    const val MEASURE = "measure"
    const val MEASUREMENT_ARG = "measurement"
    const val MEASUREMENT = "measure/{$MEASUREMENT_ARG}"
    fun measurement(measurement: Measurement) = "measure/${measurement.name}"
    const val QUICK_LOG = "quick_log"
    const val LOG_WEIGHT = "log_weight"
    const val LOG_BLOOD_PRESSURE = "log_blood_pressure"
    const val BREATHE = "breathe"
}
