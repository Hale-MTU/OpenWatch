package tech.mmarca.openwatch.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.util.Log
import tech.mmarca.healthcalculations.signal.HrvRmssd
import tech.mmarca.healthcalculations.signal.HrvWindowResult
import tech.mmarca.healthcalculations.signal.NightHrv
import tech.mmarca.healthcalculations.signal.PpgBeats

/**
 * What one night window gave: its RMSSD and its breathing rate, either may
 * be missing. [breathingRoutes] counts the routes the rate rests on: two for
 * the raw PPG when its beat intervals and signal baseline agree, one when only
 * one of them had a clear peak, and one for a source that reports only intervals.
 */
internal class NightWindowResult(
    val hrv: HrvWindowResult?,
    val breathsPerMinute: Double? = null,
    val breathingRoutes: Int = 0,
) {
    val isEmpty: Boolean get() = hrv == null && breathsPerMinute == null
}

/**
 * Where the beats of one HRV window come from: Samsung's raw PPG
 * ([PpgWindowSource]), the Samsung platform's own intervals
 * (`samsung/SamsungIbiSource`) or, for a spot HRV, the single beats of a
 * heart-beat sensor ([HeartBeatWindowSource]), all built in [WearGraph]. The
 * night's windows ([NightHrvRecorder]) and a spot HRV ([SpotMeasurements])
 * each have instances of their own, since a source holds one window at a
 * time. Its user owns the schedule, the wake lock and what is kept; a source
 * only listens between [begin] and [finish]. Every call is on the user's
 * handler thread, and so is every [finish] callback.
 */
internal interface HrvWindowSource {
    /** Whether this watch can serve the source at all. */
    val isAvailable: Boolean

    /** Whether the last window ended with the source not serving (an error, or nothing at all): a reason to fall back. */
    val lastWindowFailed: Boolean
        get() = false

    /** Starts listening for the window that opens at [startMillis]. False when the source refused. */
    fun begin(handler: Handler, startMillis: Long): Boolean

    /** Stops listening and hands over the window's result, null for none, possibly after a short wait. */
    fun finish(endMillis: Long, onResult: (NightWindowResult) -> Unit)

    /** Stops listening and drops the window. */
    fun cancel()
}

/**
 * Samsung's raw PPG, `com.samsung.sensor.hr_raw`, found by its string type,
 * 25 Hz, under the heart rate permission the app already holds; the beats
 * are found here ([PpgBeats], as [NightHrv] finds them), and the RMSSD rests
 * on at least [minCleanBeats] clean ones: a night's five minutes need the
 * library's 100, a spot's three minutes fewer. It is registered unbatched for
 * the window; the batched sensor's gaps lose beats. The raw signal likely
 * gets light only while the heart rate sensor runs, so a spot keeps that
 * one registered beside it.
 */
internal class PpgWindowSource(
    context: Context,
    private val debug: DebugProtocol,
    private val minCleanBeats: Int = HrvRmssd.MIN_CLEAN_BEATS,
    /** The debug event a finished window is logged as. */
    private val windowEvent: String = "ppg_window",
) : HrvWindowSource, SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getSensorList(Sensor.TYPE_ALL).firstOrNull {
        it.stringType == PpgRawLogger.SENSOR_TYPE
    }

    private var startedAt = 0L
    private var times = DoubleArray(INITIAL_CAPACITY)
    private var values = DoubleArray(INITIAL_CAPACITY)
    private var count = 0

    override val isAvailable: Boolean
        get() = sensor != null

    override fun begin(handler: Handler, startMillis: Long): Boolean {
        val raw = sensor ?: return false
        count = 0
        startedAt = startMillis
        val registered = try {
            sensorManager.registerListener(this, raw, raw.minDelay.coerceAtLeast(1), 0, handler)
                .also { if (it) debug.registered("ppgWindow", raw, raw.minDelay.coerceAtLeast(1), 0) }
        } catch (_: SecurityException) {
            Log.w(TAG, "Raw PPG refused")
            false
        }
        if (!registered) startedAt = 0L
        return registered
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (startedAt == 0L || event.values.size <= PPG_CHANNEL || count >= MAX_SAMPLES) return
        if (SensorTime.epochMillisOf(event.timestamp) < startedAt - STALE_MILLIS) return
        if (count == times.size) {
            times = times.copyOf(count * 2)
            values = values.copyOf(count * 2)
        }
        times[count] = event.timestamp / 1_000_000.0
        // The channel carries an integer's bits in a float.
        values[count] = java.lang.Float.floatToRawIntBits(event.values[PPG_CHANNEL]).toDouble()
        count++
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun finish(endMillis: Long, onResult: (NightWindowResult) -> Unit) {
        val t = times.copyOf(count)
        val v = values.copyOf(count)
        val window = startedAt
        cancel()
        val result = PpgBeats.detect(t, v)?.let { HrvRmssd.of(it, minCleanBeats) }
        val breathing = NightHrv.respiration(t, v)
        debug.event(
            "NightHrv", windowEvent,
            "startedAt" to window, "samples" to t.size, "rmssd" to result?.rmssdMillis, "beats" to result?.beatCount,
            "rrIntervals" to breathing.fromIntervals?.breathsPerMinute, "rrIntervalsShare" to breathing.fromIntervals?.peakShare,
            "rrSignal" to breathing.fromSignal?.breathsPerMinute, "rrSignalShare" to breathing.fromSignal?.peakShare,
            "rr" to breathing.value,
        )
        // Both routes when they agree, one alone when only it found a clear peak (health-calculations' RespiratoryRate.Window).
        onResult(NightWindowResult(result, breathing.value, breathing.routes))
    }

    override fun cancel() {
        sensorManager.unregisterListener(this)
        startedAt = 0L
        count = 0
    }

    private companion object {
        const val TAG = "PpgWindowSource"

        /** Raw PPG channel `v5`: the pulsatile one, found by the research spike. */
        const val PPG_CHANNEL = 5

        /** A cached reading from before the window started. */
        const val STALE_MILLIS = 1_000L

        /** 25 Hz for five minutes is 7 500; more than this is not kept. */
        const val MAX_SAMPLES = 25 * 330
        const val INITIAL_CAPACITY = 8_192
    }
}
