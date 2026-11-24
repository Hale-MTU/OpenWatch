package tech.mmarca.openwatch.wear

import android.Manifest
import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSensor
import tech.mmarca.openwatch.wearlink.WearLinkEntry
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

/** What the tiles and complications read: one read each, so a metric shows the same number on every one. */
@RunWith(AndroidJUnit4::class)
class MetricReadsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = MetricStore(app, name = null)
    /** 2023-11-15, 10:00 UTC. */
    private val day = TileDay.of(1_700_042_400_000L, ZoneOffset.UTC)

    @After
    fun close() = store.close()

    @Test
    fun `steps say why when the watch has no counter or the grant is off`() {
        assertEquals(StepsToday.Missing(R.string.tile_steps_no_sensor), readStepsToday(app, store, day))

        addStepCounter()
        assertEquals(StepsToday.Missing(R.string.tile_not_granted), readStepsToday(app, store, day))
    }

    @Test
    fun `today's steps are the minutes since midnight, yesterday's left out`() {
        addStepCounter()
        shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        store.putAll(
            WearMetrics.STEPS,
            listOf(
                WearLinkProtocol.StepMinute(day.startMillis - TileDay.MINUTE_MILLIS, 500),
                WearLinkProtocol.StepMinute(day.startMillis + 60 * TileDay.MINUTE_MILLIS, 120),
                WearLinkProtocol.StepMinute(day.startMillis + 61 * TileDay.MINUTE_MILLIS, 80),
            ),
        )

        val today = readStepsToday(app, store, day) as StepsToday.Counted

        assertEquals(200L, today.total)
    }

    @Test
    fun `the heart rate is the newest sample while granted and under an hour old`() {
        val now = day.nowMillis
        assertEquals(TileHeartRate.NotGranted, readHeartRateNow(app, store, now))

        shadowOf(app).grantPermissions(WearPermissions.heartRate)
        assertEquals(TileHeartRate.None, readHeartRateNow(app, store, now))

        store.put(WearMetrics.HEART_RATE, WearLinkProtocol.HeartRateSample(now - 3 * TileDay.MINUTE_MILLIS, 71))
        assertEquals(TileHeartRate.Recent(71, minutesAgo = 3), readHeartRateNow(app, store, now))
    }

    @Test
    fun `today's water is every glass since midnight, and nothing else logged`() {
        val log = EntryLog(store, now = { day.nowMillis })
        log.log(WearLinkEntry.Type.WATER, 500.0, atMillis = day.startMillis - TileDay.MINUTE_MILLIS)
        log.log(WearLinkEntry.Type.WATER, 250.0, atMillis = day.startMillis)
        log.log(WearLinkEntry.Type.WATER, 250.0, atMillis = day.nowMillis - TileDay.MINUTE_MILLIS)
        log.log(WearLinkEntry.Type.WEIGHT, 72.0, atMillis = day.nowMillis - TileDay.MINUTE_MILLIS)

        assertEquals(500.0, readWaterToday(store, day), 0.0)
    }

    private fun addStepCounter() {
        shadowOf(app.getSystemService(SensorManager::class.java)).addSensor(ShadowSensor.newInstance(Sensor.TYPE_STEP_COUNTER))
    }
}
