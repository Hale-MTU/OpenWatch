package tech.mmarca.openwatch.devices.garmin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Last-known phone position for the watch. A passive read; no fix is ever requested. */
@Singleton
class GarminPhoneLocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun lastKnown(): GarminPhoneLocation? {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null

        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val best = manager.allProviders
            .mapNotNull { provider ->
                runCatching {
                    @Suppress("MissingPermission")
                    manager.getLastKnownLocation(provider)
                }.getOrNull()
            }
            .maxByOrNull { it.time }
            ?: return null
        return GarminPhoneLocation(
            latitude = best.latitude,
            longitude = best.longitude,
            altitudeMeters = best.altitude,
            time = Instant.ofEpochMilli(best.time),
            horizontalAccuracyMeters = best.accuracy,
            verticalAccuracyMeters = best.verticalAccuracyMeters,
            bearingDegrees = best.bearing,
            speedMetersPerSecond = best.speed,
        )
    }
}
