package tech.mmarca.openwatch.data.local.garmin

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Watch-only wellness samples with no Health Connect type: this table is
 * their system of record. One table with a [metric] discriminator; the
 * `(metric, time_millis)` key makes re-imports idempotent. Column-identical
 * to the Flutter drift table.
 */
@Entity(
    tableName = "garmin_wellness_samples",
    primaryKeys = ["metric", "time_millis", "device_salt"],
)
data class GarminWellnessSampleEntity(
    /** A [tech.mmarca.openwatch.domain.model.GarminWellnessMetric.storageName]. */
    @ColumnInfo(name = "metric") val metric: String,
    /** Sample instant, UTC milliseconds since the epoch. */
    @ColumnInfo(name = "time_millis") val timeMillis: Long,
    /** Stored raw, uninterpreted, in the metric's own units. */
    @ColumnInfo(name = "value") val value: Long,
    /** The id salt of the watch it came from; empty for a family's first, and for rows from before. */
    @ColumnInfo(name = "device_salt", defaultValue = "") val deviceSalt: String = "",
)
