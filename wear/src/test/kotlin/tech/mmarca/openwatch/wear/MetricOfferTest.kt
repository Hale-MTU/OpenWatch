package tech.mmarca.openwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricOfferTest {

    private val dayMetricsMeasuredHere = MetricTile.entries
        .filter { it.source == MetricSource.SENSOR && it.period == MetricPeriod.DAY }
        .toSet()

    @Test
    fun `a full watch offers every day metric it can measure, but VO2 max`() {
        assertEquals(dayMetricsMeasuredHere - MetricTile.VO2_MAX, MetricOffer.recorded(WatchSensors.ALL))
        val available = MetricOffer.available(WatchSensors.ALL, withReadings = emptySet())
        assertTrue(available.containsAll(dayMetricsMeasuredHere - MetricTile.VO2_MAX))
        assertFalse(MetricTile.VO2_MAX in available)
    }

    @Test
    fun `a bare watch still offers what the phone sends and what is logged by hand`() {
        val available = MetricOffer.available(WatchSensors.NONE, withReadings = emptySet())

        assertEquals(MetricTile.entries.filter { it.source != MetricSource.SENSOR }.toSet(), available)
        assertTrue(MetricTile.SLEEP in available)
        assertTrue(MetricTile.HYDRATION in available)
        assertTrue(MetricTile.WEIGHT in available)
        assertFalse(MetricTile.STEPS in available)
        assertFalse(MetricTile.HEART_RATE in available)
    }

    @Test
    fun `each day metric follows its sensor`() {
        assertEquals(
            setOf(MetricTile.STEPS, MetricTile.DISTANCE, MetricTile.ACTIVE_CALORIES),
            MetricOffer.recorded(WatchSensors.NONE.copy(stepCounter = true)),
        )
        assertEquals(setOf(MetricTile.HEART_RATE), MetricOffer.recorded(WatchSensors.NONE.copy(heartRate = true, heartBeat = true)))
        assertEquals(setOf(MetricTile.FLOORS, MetricTile.ELEVATION), MetricOffer.recorded(WatchSensors.NONE.copy(barometer = true, gps = true)))
    }

    @Test
    fun `a night's vital is offered once the watch holds a reading of it, whatever the sensors`() {
        assertFalse(MetricTile.SKIN_TEMPERATURE in MetricOffer.available(WatchSensors.ALL, withReadings = emptySet()))
        assertFalse(MetricTile.HRV in MetricOffer.available(WatchSensors.ALL, withReadings = emptySet()))
        assertTrue(MetricTile.SKIN_TEMPERATURE in MetricOffer.available(WatchSensors.NONE, setOf(MetricTile.SKIN_TEMPERATURE)))
        assertTrue(MetricTile.BLOOD_OXYGEN in MetricOffer.available(WatchSensors.NONE, setOf(MetricTile.BLOOD_OXYGEN)))
    }

    @Test
    fun `a day metric the sensor list does not show is offered once a reading of it is there`() {
        assertTrue(MetricTile.STEPS in MetricOffer.available(WatchSensors.NONE, setOf(MetricTile.STEPS)))
    }
}
