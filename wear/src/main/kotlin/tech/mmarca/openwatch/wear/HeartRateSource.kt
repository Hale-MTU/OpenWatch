package tech.mmarca.openwatch.wear

import android.os.Handler
import tech.mmarca.openwatch.wearlink.HeartRateInterval

/**
 * Where the heart rate comes from: the Android sensor ([SensorHeartRateSource])
 * or the Samsung Health Platform (`samsung/SamsungHeartRateSource`), chosen in
 * [WearGraph]. [HeartRateRecorder] owns the thread, the store, the choice
 * between the two and the fall-back; a source only measures between [start]
 * and [stop], and calls its [Sink] on the handler it was started with.
 */
internal interface HeartRateSource {

    /** Whether this watch can serve the source at all. */
    val isAvailable: Boolean

    /** Starts measuring at [interval]. False when the source refused (no sensor, a refused registration or bind). */
    fun start(interval: HeartRateInterval, handler: Handler, sink: Sink): Boolean

    /** Stops measuring; safe to call when not started. */
    fun stop()

    /** The power-save alarm fired. Only a source that schedules its own windows acts on it. Any thread. */
    fun onAlarm() = Unit

    /** What a source hands the recorder. */
    interface Sink {
        /** A usable reading: in contact, reliable, a rate a heart has. */
        fun onReading(epochMillis: Long, bpm: Int)

        /** No contact with the wrist, or a reading the source marked unusable. */
        fun onNoContact(epochMillis: Long)

        /** The source failed or stayed silent, for [why]; the recorder falls back to the sensor. */
        fun onFailed(why: String)
    }
}
