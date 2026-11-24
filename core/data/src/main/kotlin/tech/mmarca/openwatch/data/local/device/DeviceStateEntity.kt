package tech.mmarca.openwatch.data.local.device

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * One value an integration keeps for one device: a cursor, a toggle, a capability list. Goes
 * with its device's row ([ForeignKey.CASCADE]), so removing a device forgets them all. Secrets
 * (a bind key, a link token) are not kept here.
 */
@Entity(
    tableName = "device_state",
    primaryKeys = ["device_id", "key"],
    foreignKeys = [
        ForeignKey(
            entity = DeviceEntity::class,
            parentColumns = ["id"],
            childColumns = ["device_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DeviceStateEntity(
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String,
)
