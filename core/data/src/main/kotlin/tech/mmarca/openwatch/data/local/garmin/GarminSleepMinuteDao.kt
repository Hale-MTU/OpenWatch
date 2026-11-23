package tech.mmarca.openwatch.data.local.garmin

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GarminSleepMinuteDao {

    /** Upserts a batch; a re-synced file rewrites the same minutes. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMinutes(minutes: List<GarminSleepMinuteEntity>)

    /** One watch's minutes in `[fromMillis, toMillis)`, by its id salt, oldest first. */
    @Query(
        "SELECT * FROM garmin_sleep_minutes " +
            "WHERE time_millis >= :fromMillis AND time_millis < :toMillis AND device_salt = :deviceSalt " +
            "ORDER BY time_millis",
    )
    suspend fun minutesBetween(fromMillis: Long, toMillis: Long, deviceSalt: String): List<GarminSleepMinuteEntity>

    /** Drops minutes older than [beforeMillis]. */
    @Query("DELETE FROM garmin_sleep_minutes WHERE time_millis < :beforeMillis")
    suspend fun pruneBefore(beforeMillis: Long)

    /** Total rows held, for diagnostics. */
    @Query("DELETE FROM garmin_sleep_minutes")
    suspend fun deleteAll()

    @Query("SELECT COUNT(time_millis) FROM garmin_sleep_minutes")
    suspend fun count(): Long
}
