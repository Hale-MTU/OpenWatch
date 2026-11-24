package tech.mmarca.openwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearRoutesTest {

    /** The argument the navigation host reads back from a metric's route. */
    private fun argumentOf(route: String): String = route.substringAfter("metric/")

    @Test
    fun everyMetricsRouteNamesItsMetric() {
        for (metric in MetricTile.entries) {
            assertEquals(metric, WearRoutes.metricOf(argumentOf(WearRoutes.metric(metric))))
        }
    }

    @Test
    fun aNameWithoutAMetricIsNoMetric() {
        // A name a later build stored, another case, or no argument at all: none throws.
        assertNull(WearRoutes.metricOf("BODY_BATTERY"))
        assertNull(WearRoutes.metricOf("steps"))
        assertNull(WearRoutes.metricOf(""))
        assertNull(WearRoutes.metricOf(null))
    }
}
