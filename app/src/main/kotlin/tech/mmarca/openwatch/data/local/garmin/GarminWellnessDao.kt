package tech.mmarca.openwatch.data.local.garmin

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GarminWellnessDao {

    /** Upserts a batch; an overlapping window rewrites the same rows. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSamples(samples: List<GarminWellnessSampleEntity>)

    /** One watch's samples for [metric] in `[fromMillis, toMillis)`, by its id salt, oldest first. */
    @Query(
        "SELECT * FROM garmin_wellness_samples " +
            "WHERE metric = :metric AND time_millis >= :fromMillis AND time_millis < :toMillis " +
            "AND device_salt = :deviceSalt ORDER BY time_millis",
    )
    suspend fun samplesBetween(metric: String, fromMillis: Long, toMillis: Long, deviceSalt: String): List<GarminWellnessSampleEntity>

    /** One watch's most recent sample for [metric], or null when none has been synced. */
    @Query(
        "SELECT * FROM garmin_wellness_samples WHERE metric = :metric AND device_salt = :deviceSalt " +
            "ORDER BY time_millis DESC LIMIT 1",
    )
    suspend fun latest(metric: String, deviceSalt: String): GarminWellnessSampleEntity?

    /** Total rows held, for diagnostics. */
    @Query("DELETE FROM garmin_wellness_samples")
    suspend fun deleteAll()

    @Query("SELECT COUNT(time_millis) FROM garmin_wellness_samples WHERE metric = :metric")
    suspend fun countFor(metric: String): Long
}
