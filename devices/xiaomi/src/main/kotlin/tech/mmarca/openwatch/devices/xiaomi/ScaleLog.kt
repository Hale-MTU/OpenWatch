package tech.mmarca.openwatch.devices.xiaomi

import android.util.Log
import tech.mmarca.openwatch.devices.core.DeviceLog

/**
 * What the scale pipeline did, step by step, for debug builds only: a
 * weigh-in that never arrives leaves no other trace. Events, never values:
 * no key, no payload, no measurement, no address. Without an installed
 * sink, logging is a no-op, which is what a JVM test gets.
 */
internal object ScaleLog {

    private const val TAG = "OpenWatchScale"

    @Volatile
    private var sink: ((String) -> Unit)? = null

    /** Routes logs to logcat. A no-op in a release build. */
    fun installLogcatSink() {
        // Guarded: the JVM `Log` stub throws.
        if (DeviceLog.isEnabled) sink = { message -> runCatching { Log.d(TAG, message) } }
    }

    fun log(message: String) {
        sink?.invoke(message)
    }
}
