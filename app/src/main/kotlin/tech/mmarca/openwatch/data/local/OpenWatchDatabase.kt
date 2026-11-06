package tech.mmarca.openwatch.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import tech.mmarca.openwatch.data.local.garmin.GarminSleepMinuteDao
import tech.mmarca.openwatch.data.local.garmin.GarminSleepMinuteEntity
import tech.mmarca.openwatch.data.local.garmin.GarminWellnessDao
import tech.mmarca.openwatch.data.local.garmin.GarminWellnessSampleEntity
import tech.mmarca.openwatch.data.local.scale.ScaleWeighInDao
import tech.mmarca.openwatch.data.local.scale.ScaleWeighInEntity

/**
 * What the devices keep on the phone: the Garmin watch-only samples and sleep
 * minutes Health Connect has no record type for, and the scale's weigh-ins
 * until each is written. Table and column names match OpenVitals' so the
 * entities and DAOs are shared text.
 *
 * Room is at version 1. A new entity means a `MIGRATION_1_2` and a bump, not
 * `fallbackToDestructiveMigration`.
 */
@Database(
    entities = [
        GarminWellnessSampleEntity::class,
        GarminSleepMinuteEntity::class,
        ScaleWeighInEntity::class,
    ],
    version = OpenWatchDatabase.VERSION,
    exportSchema = true,
)
abstract class OpenWatchDatabase : RoomDatabase() {
    abstract fun garminWellnessDao(): GarminWellnessDao
    abstract fun garminSleepMinuteDao(): GarminSleepMinuteDao
    abstract fun scaleWeighInDao(): ScaleWeighInDao

    companion object {
        const val VERSION = 1
        const val NAME = "openwatch.db"
    }
}
