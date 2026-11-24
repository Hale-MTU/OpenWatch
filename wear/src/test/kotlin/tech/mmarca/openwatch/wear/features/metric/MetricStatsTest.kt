package tech.mmarca.openwatch.wear.features.metric

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.mmarca.openwatch.wear.MetricTile
import tech.mmarca.openwatch.wear.MetricUiState
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.SampleStats

class MetricStatsTest {

    @Test
    fun `the heart rate's low and high are its samples', not its line's`() {
        // The line's five-minute means run from 70 to 88; the readings from 60 to 95, the latest one.
        val state = MetricUiState(
            current = 95.0,
            today = listOf(70.0, 88.0, 72.0),
            todayStats = SampleStats(low = 60.0, average = 76.0, high = 95.0),
        )

        val stats = metricStats(MetricTile.HEART_RATE, state, restingHeartRate = null)

        assertEquals(
            listOf(MetricStat(R.string.stat_low, 60.0), MetricStat(R.string.stat_average, 76.0), MetricStat(R.string.stat_high, 95.0)),
            stats,
        )
    }

    @Test
    fun `a metric whose chart holds its samples takes the three from them`() {
        val state = MetricUiState(current = 60.0, today = listOf(40.0, 50.0, 60.0))

        val stats = metricStats(MetricTile.HRV, state, restingHeartRate = null)

        assertEquals(
            listOf(MetricStat(R.string.stat_low, 40.0), MetricStat(R.string.stat_average, 50.0), MetricStat(R.string.stat_high, 60.0)),
            stats,
        )
    }

    @Test
    fun `a night's value is the headline, so only its lowest and highest reading get a row`() {
        val state = MetricUiState(current = 35.0, today = listOf(34.8, 35.1), todayStats = SampleStats(low = 34.0, average = null, high = 36.0))

        val stats = metricStats(MetricTile.SKIN_TEMPERATURE, state, restingHeartRate = null)

        assertEquals(listOf(MetricStat(R.string.stat_low, 34.0), MetricStat(R.string.stat_high, 36.0)), stats)
    }

    @Test
    fun `a sampled metric without a sample today has no low or high`() {
        assertEquals(emptyList<MetricStat>(), metricStats(MetricTile.HEART_RATE, MetricUiState(current = 58.0), restingHeartRate = null))
    }
}
