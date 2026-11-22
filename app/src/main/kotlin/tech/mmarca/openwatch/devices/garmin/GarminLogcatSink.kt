package tech.mmarca.openwatch.devices.garmin

import android.util.Log
import tech.mmarca.openwatch.BuildConfig

private const val TAG = "GarminGfdi"

/**
 * Routes [GarminLog] to logcat, through its redaction. A no-op in a release build: that is
 * the whole redaction policy. The Android half of [GarminLog], which the protocol module
 * cannot hold.
 */
fun GarminLog.installLogcatSink() {
    // Guarded: the JVM `Log` stub throws, and this sink is global across tests.
    if (BuildConfig.DEBUG) installSink { message -> runCatching { Log.d(TAG, message) } }
}
