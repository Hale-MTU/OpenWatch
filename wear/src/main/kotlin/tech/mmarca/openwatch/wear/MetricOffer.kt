package tech.mmarca.openwatch.wear

/**
 * Which metrics a watch offers, from what it has to measure with
 * ([WatchSensors]) and what it holds readings of. Pure, so the choice is
 * tested off the watch.
 */
internal object MetricOffer {

    /**
     * The metrics to offer as tiles ([WearUiState.availableMetrics]): every
     * metric from the phone or logged by hand; one the watch measures itself
     * where its sensor is there ([recorded]), or once the watch holds a
     * reading of it ([withReadings]); a night's vital only once it holds a
     * reading, since the sensor list does not say whether the raw PPG or the
     * Samsung platform will give one at night.
     */
    fun available(sensors: WatchSensors, withReadings: Set<MetricTile>): Set<MetricTile> {
        val recorded = recorded(sensors)
        return MetricTile.entries.filterTo(LinkedHashSet()) { metric ->
            when {
                metric.source != MetricSource.SENSOR -> true
                metric.period == MetricPeriod.NIGHT -> metric in withReadings
                else -> metric in recorded || metric in withReadings
            }
        }
    }

    /**
     * The day's metrics the watch records with [sensors]: the steps from the
     * step counter, and the distance and active calories, which come from
     * the steps; the heart rate from its sensor; the floors and the height
     * climbed from the barometer. VO2 max has no source on the watch.
     */
    fun recorded(sensors: WatchSensors): Set<MetricTile> = buildSet {
        if (sensors.stepCounter) addAll(listOf(MetricTile.STEPS, MetricTile.DISTANCE, MetricTile.ACTIVE_CALORIES))
        if (sensors.heartRate) add(MetricTile.HEART_RATE)
        if (sensors.barometer) addAll(listOf(MetricTile.FLOORS, MetricTile.ELEVATION))
    }
}
