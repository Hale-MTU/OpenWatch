package tech.mmarca.openwatch.wear.workout

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowSystemClock
import tech.mmarca.openwatch.wear.DebugProtocol
import tech.mmarca.openwatch.wear.MeasurementSource
import tech.mmarca.openwatch.wear.MetricStore
import tech.mmarca.openwatch.wear.WatchPreferences
import tech.mmarca.openwatch.wearlink.HeartRateInterval
import tech.mmarca.openwatch.wearlink.WorkoutSport

/** The wake lock a running workout holds: the app's tag, and a timeout the recorder's tick renews. */
@RunWith(AndroidJUnit4::class)
class WorkoutRecorderWakeLockTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = MetricStore(app, name = null)
    private val settings = WatchPreferences(
        heartRateDay = HeartRateInterval.EVERY_10_SECONDS,
        heartRateSource = MeasurementSource.RAW_SENSOR,
        hrvSource = MeasurementSource.RAW_SENSOR,
        syncedSleepHold = false,
        skinTemperature = false,
        spo2AtNight = false,
    )
    private val recorder = WorkoutRecorder(app, store, WorkoutState(), DebugProtocol.off(), settings = { settings }) {}

    @After
    fun tearDown() {
        recorder.release()
        store.close()
    }

    @Test
    fun `a workout holds a timed wake lock under the app's own tag`() {
        assertTrue(recorder.start(WorkoutSport.YOGA, useGps = false))

        val lock = ShadowPowerManager.getLatestWakeLock()
        val shadow = shadowOf(lock)
        assertTrue(lock.isHeld)
        assertEquals("OpenWatch:workout", shadow.tag)
        assertFalse("a renewal moves the one timeout on", shadow.isReferenceCounted)

        // Nothing renews it here (the tick renews on the wall clock): the hold lapses after ten minutes.
        ShadowSystemClock.advanceBy(Duration.ofMinutes(9))
        assertTrue(lock.isHeld)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        assertFalse(lock.isHeld)
    }
}
