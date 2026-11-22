package tech.mmarca.openwatch.data.local.device

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One paired device: the registry row `BleDeviceRepository` reads into memory. Every field is
 * stored as text or a number the domain model maps, so a row from a newer build, an integration
 * or kind this version does not know, stays in the table unread rather than taken for something
 * else.
 */
@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    /** A `DeviceIntegration.storageName`, or null for a plain sensor and a legacy Garmin watch. */
    @ColumnInfo(name = "integration") val integration: String?,
    /** A `BleDeviceKind.storageName`. */
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "address") val address: String,
    @ColumnInfo(name = "bluetooth_name") val bluetoothName: String?,
    /** `BleSensorCapability` names, comma-separated. */
    @ColumnInfo(name = "capabilities") val capabilities: String,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
    @ColumnInfo(name = "wheel_circumference_mm") val wheelCircumferenceMm: Int?,
    @ColumnInfo(name = "battery_percent") val batteryPercent: Int?,
    /** UTC milliseconds since the epoch, as every time here. */
    @ColumnInfo(name = "battery_updated_at") val batteryUpdatedAt: Long?,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long?,
    /**
     * What this device adds to its records' ids and its rows' keys, so two devices of one family
     * do not overwrite each other. Empty for the first device of a family, whose ids and rows
     * were there before the salt was.
     */
    @ColumnInfo(name = "id_salt") val idSalt: String,
)
