package tech.mmarca.openwatch.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import kotlin.math.sqrt

/**
 * A sensor a spot measurement listens to while it runs ([SpotMeasurements]):
 * [listen] registers [onReading] and returns the handle that unregisters it;
 * null when the registration failed or was refused.
 */
internal fun interface SpotSensor<T> {
    fun listen(onReading: (T) -> Unit): AutoCloseable?
}

/**
 * Registers [onEvent] for [sensor] at [samplingMicros], unbatched, on a
 * thread of its own named [name], and returns the handle that unregisters it
 * and ends the thread. Null when the registration failed, or was refused for
 * a grant that is not in.
 */
internal fun SensorManager.listenOnOwnThread(
    sensor: Sensor,
    samplingMicros: Int,
    name: String,
    onEvent: (SensorEvent) -> Unit,
): AutoCloseable? {
    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) = onEvent(event)

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    val worker = HandlerThread(name).apply { start() }
    val registered = try {
        registerListener(listener, sensor, samplingMicros, 0, Handler(worker.looper))
    } catch (_: SecurityException) {
        // A missing grant: the caller says so to the wearer, from its own grant check.
        false
    }
    if (!registered) {
        worker.quitSafely()
        return null
    }
    return AutoCloseable {
        unregisterListener(listener)
        worker.quitSafely()
    }
}

/**
 * The heart-beat sensor (`TYPE_HEART_BEAT`), where the watch has one: the
 * time of each beat, in elapsed-realtime milliseconds, for a spot HRV. Under
 * the heart rate grant.
 */
internal class HeartBeatSensor(context: Context) : SpotSensor<Double> {

    private val sensorManager = context.getSystemService(SensorManager::class.java)

    override fun listen(onReading: (Double) -> Unit): AutoCloseable? {
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_BEAT) ?: return null
        return sensorManager.listenOnOwnThread(sensor, SensorManager.SENSOR_DELAY_FASTEST, "HeartBeatSensor") { event ->
            onReading(event.timestamp / NANOS_PER_MILLI)
        }
    }

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000.0

        /** Whether the watch reports single beats at all; without them there is no spot HRV. */
        fun hasSensor(context: Context): Boolean =
            context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_HEART_BEAT) != null
    }
}

/** One accelerometer reading: its time in elapsed-realtime milliseconds and its magnitude in m/s². */
internal class Motion(val timeMillis: Long, val magnitude: Double)

/**
 * The accelerometer at its normal rate (five readings a second), unbatched,
 * for a spot HRV, which keeps its reading only when the arm stayed still
 * ([Stillness]): its own listener beside the sleep recorder's batched one.
 * No grant.
 */
internal class MotionSensor(context: Context) : SpotSensor<Motion> {

    private val sensorManager = context.getSystemService(SensorManager::class.java)

    override fun listen(onReading: (Motion) -> Unit): AutoCloseable? {
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return null
        return sensorManager.listenOnOwnThread(sensor, SensorManager.SENSOR_DELAY_NORMAL, "MotionSensor") { event ->
            if (event.values.size < AXES) return@listenOnOwnThread
            val (x, y, z) = event.values
            onReading(Motion(event.timestamp / NANOS_PER_MILLI, sqrt((x * x + y * y + z * z).toDouble())))
        }
    }

    private companion object {
        const val AXES = 3
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * The barometer (`TYPE_PRESSURE`) once a second, unbatched, for a spot
 * reading of the air pressure: its own listener, beside the ascent
 * recorder's batched one. No grant. A reading the sensor cached long ago is
 * dropped, as the recorders drop it.
 */
internal class PressureSensor(context: Context) : SpotSensor<Float> {

    private val sensorManager = context.getSystemService(SensorManager::class.java)

    override fun listen(onReading: (Float) -> Unit): AutoCloseable? {
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return null
        return sensorManager.listenOnOwnThread(sensor, SAMPLING_PERIOD_MICROS, "PressureSensor") { event ->
            val hectopascals = event.values.firstOrNull()?.takeIf { it.isFinite() && it > 0f } ?: return@listenOnOwnThread
            if (!SensorTime.isStale(SensorTime.epochMillisOf(event.timestamp))) onReading(hectopascals)
        }
    }

    private companion object {
        const val SAMPLING_PERIOD_MICROS = 1_000_000
    }
}
