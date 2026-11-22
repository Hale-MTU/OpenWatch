package tech.mmarca.openwatch.devices.weather

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.concurrent.thread
import tech.mmarca.openwatch.devices.core.DeviceLog

/**
 * Receives weather from a companion app (Breezy Weather) over the
 * generic-weather broadcast. This is the only weather source. Exported on
 * purpose: the payload is public weather, and freshness is capped. Any app can
 * send it, so [GenericWeatherPayload] caps every size before it parses and the
 * store keeps one a minute. The sender is not checked: a broadcast names its
 * sender only when the sender opts in (Android 14 and newer), and the companion
 * apps do not.
 */
@AndroidEntryPoint
class GenericWeatherReceiver : BroadcastReceiver() {

    @Inject
    lateinit var store: WeatherStore

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACCEPTED_ACTIONS) return
        val bundle = intent.extras ?: return
        val json = bundle.getString(EXTRA_WEATHER_JSON)
        val gzipped = bundle.getByteArray(EXTRA_WEATHER_GZ)
        if (json == null && gzipped == null) return
        // Inflating and parsing do not belong on the main thread.
        val pending = goAsync()
        thread(name = "generic-weather") {
            try {
                val snapshot = GenericWeatherPayload.decode(json, gzipped) ?: return@thread
                // Counts only: the place and the temperatures are the user's.
                if (store.save(snapshot)) {
                    Log.log("received ${snapshot.hourly.size}h/${snapshot.daily.size}d forecast")
                } else {
                    Log.log("dropped a forecast within a minute of the last")
                }
            } catch (error: Exception) {
                Log.log("broken weather broadcast: ${error::class.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** The generic-weather action, plus a native alias. */
        val ACCEPTED_ACTIONS = setOf(
            "nodomain.freeyourgadget.gadgetbridge.ACTION_GENERIC_WEATHER",
            "tech.mmarca.openwatch.ACTION_GENERIC_WEATHER",
        )
        const val EXTRA_WEATHER_JSON = "WeatherJson"
        const val EXTRA_WEATHER_GZ = "WeatherGz"
    }
}

private val Log = DeviceLog("Weather")
