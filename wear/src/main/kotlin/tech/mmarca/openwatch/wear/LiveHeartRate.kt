package tech.mmarca.openwatch.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

/**
 * The heart rate as it happens, beside the recorder's batched listener and
 * its wake-ups, which stay as they are; nothing is stored. Built once in
 * [WearGraph].
 *
 * - [window]: one `HR_LIVE` window at a time per connection, for a phone
 *   that is showing it. Only while [isRecording]: the recorder runs only
 *   when the heart rate is granted and the watch is worn ([WornMonitor]),
 *   and the phone's view must not light the sensor of a watch lying on a
 *   table.
 * - [listen]: a registration that stays until it is closed, for a spot
 *   measurement the wearer started on the watch ([SpotMeasurements]); not
 *   gated on the recorder, since the wearer asks for it.
 */
class LiveHeartRate(
    context: Context,
    private val isRecording: () -> Boolean,
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /** One reading of the sensor, as a listener of its own gets it. */
    sealed interface Reading {
        /** A usable sample. */
        data class Sample(val sample: WearLinkProtocol.HeartRateSample) : Reading

        /** The sensor has no contact with skin: the watch is off the wrist, or loose. */
        data object NoContact : Reading

        /** The sensor reads, but nothing a heart beats at yet: still locking on. */
        data object Unreliable : Reading
    }

    /** Whether the watch has a heart rate sensor at all; the link announces `hl` then. */
    val hasSensor: Boolean
        get() = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE) != null

    /**
     * Registers a listener of its own, without batching, and hands each
     * reading to [onReading] on a thread of its own until the returned handle
     * is closed. A reading the sensor cached long ago, which a new listener is
     * handed first, is dropped. Null when the watch has no sensor or the
     * registration failed.
     */
    fun listen(onReading: (Reading) -> Unit): AutoCloseable? {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE) ?: return null
        val handle = sensorManager.listenOnOwnThread(sensor, SAMPLING_PERIOD_MICROS, "LiveHeartRate") { event ->
            val bpm = event.values.firstOrNull()?.toInt() ?: return@listenOnOwnThread
            val at = SensorTime.epochMillisOf(event.timestamp)
            if (SensorTime.isStale(at)) return@listenOnOwnThread
            onReading(
                when {
                    event.accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT -> Reading.NoContact
                    isUsableHeartRate(event.accuracy, bpm) -> Reading.Sample(WearLinkProtocol.HeartRateSample(at, bpm))
                    else -> Reading.Unreliable
                },
            )
        }
        if (handle == null) Log.w(TAG, "Heart rate sensor not registered")
        return handle
    }

    /** Blocks for up to [seconds], handing each usable reading to [emit] on the caller's thread; returns how many. */
    fun window(seconds: Int, emit: (WearLinkProtocol.HeartRateSample) -> Unit): Int {
        if (!isRecording()) return 0
        val readings = LinkedBlockingQueue<WearLinkProtocol.HeartRateSample>()
        val handle = listen { reading -> if (reading is Reading.Sample) readings.put(reading.sample) } ?: return 0
        var count = 0
        try {
            val deadline = System.currentTimeMillis() + seconds.coerceIn(1, WearLinkProtocol.MAX_LIVE_SECONDS) * 1000L
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                val reading = readings.poll(left, TimeUnit.MILLISECONDS) ?: break
                emit(reading)
                count++
            }
        } finally {
            handle.close()
        }
        return count
    }

    private companion object {
        const val TAG = "LiveHeartRate"

        /** One reading a second, as the sensor delivers anyway. */
        const val SAMPLING_PERIOD_MICROS = 1_000_000
    }
}
