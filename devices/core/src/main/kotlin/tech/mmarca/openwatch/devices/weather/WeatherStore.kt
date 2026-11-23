package tech.mmarca.openwatch.devices.weather

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * The last weather report broadcast, and when. One snapshot: the watch
 * wants the weather now, and stale weather is worse than none.
 */
@Singleton
class WeatherStore(private val prefs: SharedPreferences) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE))

    /**
     * Keeps [snapshot]; false when one was kept less than [MIN_INTERVAL] before. Any app may
     * send the broadcast, and each save rewrites up to half a megabyte, so a flood is cut to one
     * write a minute; a companion app refreshes far less often.
     */
    fun save(snapshot: WeatherSnapshot, receivedAt: Instant = Instant.now()): Boolean {
        val last = receivedAt()
        if (last != null && !receivedAt.isBefore(last) && Duration.between(last, receivedAt) < MIN_INTERVAL) return false
        prefs.edit {
            putString(KEY_SNAPSHOT, snapshot.toJson().toString())
            putLong(KEY_RECEIVED_AT, receivedAt.toEpochMilli())
        }
        return true
    }

    fun snapshot(): WeatherSnapshot? =
        prefs.getString(KEY_SNAPSHOT, null)?.let { stored ->
            runCatching { WeatherSnapshot.fromJson(JSONObject(stored)) }.getOrNull()
        }

    fun receivedAt(): Instant? =
        prefs.getLong(KEY_RECEIVED_AT, 0L).takeIf { it > 0L }?.let(Instant::ofEpochMilli)

    /** The snapshot if it is recent enough to show on a wrist, else null. */
    fun freshSnapshot(now: Instant = Instant.now()): WeatherSnapshot? {
        val received = receivedAt() ?: return null
        if (Duration.between(received, now) > MAX_AGE) return null
        return snapshot()
    }

    private companion object {
        const val PREFS_FILE = "weather_store"
        const val KEY_SNAPSHOT = "snapshot"
        const val KEY_RECEIVED_AT = "received_at"

        /** Companion apps refresh every 30m-2h; older than this they would not show it either. */
        val MAX_AGE: Duration = Duration.ofHours(6)

        val MIN_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
