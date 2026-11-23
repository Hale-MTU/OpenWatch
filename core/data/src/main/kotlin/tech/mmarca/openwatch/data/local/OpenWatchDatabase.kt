package tech.mmarca.openwatch.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import tech.mmarca.openwatch.data.local.device.DeviceDao
import tech.mmarca.openwatch.data.local.device.DeviceEntity
import tech.mmarca.openwatch.data.local.device.DeviceStateDao
import tech.mmarca.openwatch.data.local.device.DeviceStateEntity
import tech.mmarca.openwatch.data.local.garmin.GarminSleepMinuteDao
import tech.mmarca.openwatch.data.local.garmin.GarminSleepMinuteEntity
import tech.mmarca.openwatch.data.local.garmin.GarminWellnessDao
import tech.mmarca.openwatch.data.local.garmin.GarminWellnessSampleEntity
import tech.mmarca.openwatch.data.local.scale.ScaleWeighInDao
import tech.mmarca.openwatch.data.local.scale.ScaleWeighInEntity

/**
 * What the devices keep on the phone: the registry of paired devices and what each
 * integration keeps per device, the Garmin watch-only samples and sleep minutes Health Connect
 * has no record type for, and the scale's weigh-ins until each is written. The three device
 * tables were named as OpenVitals' are; since version 2 each also keys its rows by the device's
 * id salt, which OpenVitals' do not.
 *
 * Room is at version 2. A change means the next `MIGRATION_n_n+1` and a bump, never
 * `fallbackToDestructiveMigration`; [OpenWatchMigrations] holds them.
 */
@Database(
    entities = [
        GarminWellnessSampleEntity::class,
        GarminSleepMinuteEntity::class,
        ScaleWeighInEntity::class,
        DeviceEntity::class,
        DeviceStateEntity::class,
    ],
    version = OpenWatchDatabase.VERSION,
    exportSchema = true,
)
abstract class OpenWatchDatabase : RoomDatabase() {
    abstract fun garminWellnessDao(): GarminWellnessDao
    abstract fun garminSleepMinuteDao(): GarminSleepMinuteDao
    abstract fun scaleWeighInDao(): ScaleWeighInDao
    abstract fun deviceDao(): DeviceDao
    abstract fun deviceStateDao(): DeviceStateDao

    companion object {
        const val VERSION = 2
        const val NAME = "openwatch.db"
    }
}
