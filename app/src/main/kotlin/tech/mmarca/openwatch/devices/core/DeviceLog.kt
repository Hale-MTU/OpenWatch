package tech.mmarca.openwatch.devices.core

import android.util.Log
import tech.mmarca.openwatch.BuildConfig

/**
 * A debug-build log for device code that belongs to no one vendor. A release build logs
 * nothing through it; neither does a unit test, where the JVM's Log stub throws. Log counts,
 * states and class names here, never a value, a name or an address.
 */
class DeviceLog(private val tag: String) {
    fun log(message: String) {
        if (BuildConfig.DEBUG) runCatching { Log.d(tag, message) }
    }
}
