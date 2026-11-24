package tech.mmarca.openwatch.wear

import android.os.Handler
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.mmarca.openwatch.wearlink.HeartRateInterval

/**
 * The recorder over two fake [HeartRateSource]s: which one runs, the
 * fall-back from the platform to the sensor, and what is stored. The pure
 * piece of the sensor's power-save window, how long to wait before the next
 * window, is covered too.
 */
@RunWith(AndroidJUnit4::class)
class HeartRateRecorderTest {

    private class FakeSource(var accepts: Boolean = true) : HeartRateSource {
        val starts = ArrayList<HeartRateInterval>()
        var sink: HeartRateSource.Sink? = null
        var alarms = 0

        override val isAvailable: Boolean = true

        override fun start(interval: HeartRateInterval, handler: Handler, sink: HeartRateSource.Sink): Boolean {
            starts += interval
            if (accepts) this.sink = sink
            return accepts
        }

        override fun stop() {
            sink = null
        }

        override fun onAlarm() {
            alarms++
        }
    }

    private val store = MetricStore(ApplicationProvider.getApplicationContext(), name = null)
    private val sensor = FakeSource()
    private val samsung = FakeSource()
    private val samples = ArrayList<Pair<Long, Int>>()
    private val contact = ArrayList<Pair<Long, Boolean>>()
    private var sourceChanges = 0
    private val recorder = HeartRateRecorder(
        store,
        sensor = sensor,
        platform = samsung,
        debug = DebugProtocol.off(),
        onSourceChanged = { sourceChanges++ },
        onSample = { at, bpm -> samples += at to bpm },
        onContact = { at, on -> contact += at to on },
    )

    @After
    fun close() {
        recorder.stop()
        store.close()
    }

    @Test
    fun `a platform that fails hands over to the sensor at the same interval until a setting changes`() {
        assertTrue(recorder.setPlan(HeartRateInterval.EVERY_10_SECONDS, WearSettings.Source.SAMSUNG_PLATFORM))
        assertEquals(WearSettings.Source.SAMSUNG_PLATFORM, recorder.source)

        samsung.sink!!.onFailed("no_data")

        assertEquals(listOf(HeartRateInterval.EVERY_10_SECONDS), sensor.starts)
        assertEquals(WearSettings.Source.RAW_SENSOR, recorder.source)
        assertEquals(1, sourceChanges)

        recorder.setPlan(HeartRateInterval.POWER_SAVE, WearSettings.Source.SAMSUNG_PLATFORM)
        assertEquals(1, samsung.starts.size)

        recorder.resetFallBack()
        recorder.setPlan(HeartRateInterval.EVERY_10_SECONDS, WearSettings.Source.SAMSUNG_PLATFORM)
        assertEquals(2, samsung.starts.size)
    }

    @Test
    fun `a platform that refuses the start is replaced by the sensor at once`() {
        samsung.accepts = false

        assertTrue(recorder.setPlan(HeartRateInterval.EVERY_10_SECONDS, WearSettings.Source.SAMSUNG_PLATFORM))

        assertEquals(listOf(HeartRateInterval.EVERY_10_SECONDS), sensor.starts)
        assertEquals(WearSettings.Source.RAW_SENSOR, recorder.source)
        assertTrue(recorder.isRunning)
    }

    @Test
    fun `readings closer than ten seconds are stored once, and no contact is handed on`() {
        recorder.setPlan(HeartRateInterval.EVERY_10_SECONDS, WearSettings.Source.RAW_SENSOR)
        val sink = sensor.sink!!
        val t0 = 1_794_100_000_000L

        sink.onReading(t0, 60)
        sink.onReading(t0 + 5_000, 61)
        sink.onReading(t0 + 10_000, 62)
        sink.onNoContact(t0 + 12_000)

        assertEquals(listOf(t0 to 60, t0 + 10_000 to 62), samples)
        assertEquals(2L, store.count(WearMetrics.HEART_RATE))
        assertEquals(listOf(t0 + 12_000 to false), contact)
    }

    @Test
    fun `an unchanged plan starts nothing again, and off stops the source`() {
        recorder.setPlan(HeartRateInterval.POWER_SAVE, WearSettings.Source.RAW_SENSOR)
        recorder.setPlan(HeartRateInterval.POWER_SAVE, WearSettings.Source.RAW_SENSOR)
        assertEquals(1, sensor.starts.size)

        recorder.setPlan(HeartRateInterval.OFF, WearSettings.Source.RAW_SENSOR)
        assertFalse(recorder.isRunning)
        assertEquals(null, sensor.sink)
    }

    @Test
    fun `the heart rate is dense only at the full rate from the sensor, and the alarm reaches only a power-save sensor`() {
        recorder.setPlan(HeartRateInterval.EVERY_10_SECONDS, WearSettings.Source.RAW_SENSOR)
        assertTrue(recorder.isDense)
        recorder.onAlarmFired()

        recorder.setPlan(HeartRateInterval.POWER_SAVE, WearSettings.Source.RAW_SENSOR)
        assertFalse(recorder.isDense)
        recorder.onAlarmFired()
        assertEquals(1, sensor.alarms)

        recorder.setPlan(HeartRateInterval.EVERY_10_SECONDS, WearSettings.Source.SAMSUNG_PLATFORM)
        assertFalse(recorder.isDense)
    }

    @Test
    fun `a window that closes immediately waits a full interval`() {
        assertEquals(600_000L, nextWindowDelayMillis(elapsedSinceOpenMillis = 0L, intervalSeconds = 600))
    }

    @Test
    fun `a window that used part of its time waits the remainder`() {
        assertEquals(595_000L, nextWindowDelayMillis(elapsedSinceOpenMillis = 5_000L, intervalSeconds = 600))
    }

    @Test
    fun `a window that overran its interval waits zero, not a negative number`() {
        assertEquals(0L, nextWindowDelayMillis(elapsedSinceOpenMillis = 700_000L, intervalSeconds = 600))
    }

    @Test
    fun `the off interval waits zero too, not a negative number`() {
        assertEquals(0L, nextWindowDelayMillis(elapsedSinceOpenMillis = 1_000L, intervalSeconds = 0))
    }
}
