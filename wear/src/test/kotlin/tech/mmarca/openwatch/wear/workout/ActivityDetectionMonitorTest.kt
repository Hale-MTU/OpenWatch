package tech.mmarca.openwatch.wear.workout

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import tech.mmarca.openwatch.wear.DebugProtocol
import tech.mmarca.openwatch.wear.MetricStore
import tech.mmarca.openwatch.wear.WatchPreferences
import tech.mmarca.openwatch.wear.WearMetrics
import tech.mmarca.openwatch.wear.WearSettings
import tech.mmarca.openwatch.wearlink.HeartRateInterval
import tech.mmarca.openwatch.wearlink.WearLinkProtocol
import tech.mmarca.openwatch.wearlink.WorkoutSport

/** What the offer of a walk the watch saw reads from a real store: offered once, not during a workout, not when switched off. */
@RunWith(AndroidJUnit4::class)
class ActivityDetectionMonitorTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = MetricStore(app, name = null)

    /** A minute boundary. */
    private val t0 = 1_700_000_040_000L
    private var now = t0
    private var settings = SETTINGS
    private var running = false
    private val monitor = ActivityDetectionMonitor(
        app, store, DebugProtocol.of(app), settings = { settings }, workoutRunning = { running }, clock = { now },
    )

    @After
    fun close() = store.close()

    /** [minutes] of a brisk walk from [from], and now half a minute after the last. */
    private fun walk(from: Long, minutes: Int) {
        store.putAll(WearMetrics.STEPS, List(minutes) { WearLinkProtocol.StepMinute(from + it * 60_000L, 110) })
        now = from + minutes * 60_000L + 30_000L
    }

    @Test
    fun `a walk of a quarter hour is offered once, from where it began`() {
        walk(t0 + 60_000L, 15)

        val found = monitor.look()

        assertEquals(WorkoutSport.WALKING, found?.sport)
        assertEquals(t0 + 60_000L, found?.startMillis)
        assertEquals(15, found?.minutes)
        now += 60_000L
        assertNull("offered once", monitor.look())
    }

    @Test
    fun `nothing is offered while a workout runs, and its minutes are no walk afterwards`() {
        walk(t0 + 60_000L, 15)
        running = true
        assertNull(monitor.look())

        running = false
        now += 60_000L
        assertNull(monitor.look())
    }

    @Test
    fun `switched off, nothing is offered`() {
        settings = SETTINGS.copy(autoDetect = false)
        walk(t0 + 60_000L, 15)

        assertNull(monitor.look())
    }

    private companion object {
        val SETTINGS = WatchPreferences(
            heartRateDay = HeartRateInterval.EVERY_10_SECONDS,
            heartRateSource = WearSettings.Source.RAW_SENSOR,
            hrvSource = WearSettings.Source.RAW_SENSOR,
            syncedSleepHold = true,
            skinTemperature = false,
            spo2AtNight = false,
        )
    }
}
