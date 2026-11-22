package tech.mmarca.openwatch.healthconnect

import android.os.Build

/**
 * True when Health Connect refused a call for this app's quota. Known by type, not by text:
 * the platform's `HealthConnectException` with `ERROR_RATE_LIMIT_EXCEEDED`, which androidx
 * rewraps, so the cause chain is searched. The Health Connect APK on older Android has no such
 * type; its refusal stays an ordinary failure.
 */
fun Throwable.isHealthConnectRateLimit(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
    return generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }.any { failure ->
        failure is android.health.connect.HealthConnectException &&
            failure.errorCode == android.health.connect.HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED
    }
}
