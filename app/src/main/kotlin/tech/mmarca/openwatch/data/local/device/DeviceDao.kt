package tech.mmarca.openwatch.data.local.device

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

/**
 * The registry's rows, read once into memory and written behind it, on the registry's own
 * thread: blocking calls, never on the main thread.
 */
@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY added_at, id")
    fun all(): List<DeviceEntity>

    @Upsert
    fun upsert(device: DeviceEntity)

    /** For the one-time import: a row already there wins. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfAbsent(devices: List<DeviceEntity>)

    @Query("DELETE FROM devices WHERE id = :id")
    fun delete(id: String)
}

/** The per-device values, read once into memory and written behind it, like [DeviceDao]. */
@Dao
interface DeviceStateDao {
    @Query("SELECT * FROM device_state")
    fun all(): List<DeviceStateEntity>

    @Upsert
    fun upsert(state: DeviceStateEntity)

    @Query("DELETE FROM device_state WHERE device_id = :deviceId AND `key` = :key")
    fun delete(deviceId: String, key: String)
}
