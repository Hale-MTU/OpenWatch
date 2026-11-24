package tech.mmarca.openwatch.devices.core

import android.util.Log

/**
 * A debug-build log for device code that belongs to no one vendor. A release build logs
 * nothing through it; neither does a unit test, where the JVM's Log stub throws. Log counts,
 * states and class names here, never a value, a name or an address.
 */
class DeviceLog(private val tag: String) {
    fun log(message: String) {
        if (isEnabled) runCatching { Log.d(tag, message) }
    }

    companion object {
        /**
         * Whether the device layer's debug logs reach logcat: in a debuggable build, as the
         * app's `BuildConfig.DEBUG` says. The library's own `DEBUG` would be true in the debug
         * APK the release pipeline publishes, which is not debuggable, so the module's build
         * file sets this field to match the app's.
         */
        val isEnabled: Boolean get() = BuildConfig.DEVICE_DEBUG_LOGS
    }
}
