package tech.mmarca.openwatch.devices.weather

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.devices.FakeSharedPreferences

class WeatherStoreTest {

    private val store = WeatherStore(FakeSharedPreferences())
    private val t0 = Instant.parse("2026-11-22T08:00:00Z")
    private val snapshot = WeatherSnapshot(
        timestamp = t0.epochSecond,
        location = "Here",
        currentTempKelvin = 283,
        todayMinTempKelvin = 280,
        todayMaxTempKelvin = 288,
        currentConditionCode = 800,
        currentHumidity = 60,
        windSpeedKmh = 5.0,
        windDirectionDegrees = 180,
        uvIndex = 1.0,
        precipProbability = 0,
        dewPointKelvin = 275,
        feelsLikeTempKelvin = 282,
        latitude = 0.0,
        longitude = 0.0,
    )

    @Test
    fun `a flood of broadcasts is kept once a minute`() {
        assertTrue(store.save(snapshot, t0))
        assertFalse(store.save(snapshot, t0.plusSeconds(30)))
        assertEquals(t0, store.receivedAt())
        assertTrue(store.save(snapshot, t0.plusSeconds(60)))
        assertEquals(t0.plusSeconds(60), store.receivedAt())
    }

    @Test
    fun `a clock set back does not lock the store`() {
        store.save(snapshot, t0)

        assertTrue(store.save(snapshot, t0.minusSeconds(3600)))
    }
}
