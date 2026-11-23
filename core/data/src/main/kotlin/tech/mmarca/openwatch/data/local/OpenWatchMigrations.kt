package tech.mmarca.openwatch.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Every schema change the app has shipped, in order. Each is final once on main: the nightly ships it. */
object OpenWatchMigrations {

    /**
     * Version 2. The device registry and what integrations keep per device move into the
     * database, empty here: the registry imports its old JSON once, at its first read. The three
     * device tables key their rows by the device's id salt as well, so two devices of one family
     * keep apart; every row already there is the first device's, the empty salt.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `devices` (`id` TEXT NOT NULL, `integration` TEXT, `kind` TEXT NOT NULL, " +
                    "`display_name` TEXT NOT NULL, `address` TEXT NOT NULL, `bluetooth_name` TEXT, `capabilities` TEXT NOT NULL, " +
                    "`enabled` INTEGER NOT NULL, `wheel_circumference_mm` INTEGER, `battery_percent` INTEGER, " +
                    "`battery_updated_at` INTEGER, `added_at` INTEGER NOT NULL, `last_synced_at` INTEGER, " +
                    "`id_salt` TEXT NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `device_state` (`device_id` TEXT NOT NULL, `key` TEXT NOT NULL, " +
                    "`value` TEXT NOT NULL, PRIMARY KEY(`device_id`, `key`), FOREIGN KEY(`device_id`) " +
                    "REFERENCES `devices`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.rebuild(
                table = "garmin_wellness_samples",
                create = "(`metric` TEXT NOT NULL, `time_millis` INTEGER NOT NULL, `value` INTEGER NOT NULL, " +
                    "`device_salt` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`metric`, `time_millis`, `device_salt`))",
                columns = "`metric`, `time_millis`, `value`",
            )
            db.rebuild(
                table = "garmin_sleep_minutes",
                create = "(`time_millis` INTEGER NOT NULL, `kind` TEXT NOT NULL, `heart_rate` REAL, `movement` REAL, " +
                    "`activity` REAL, `offset_seconds` INTEGER NOT NULL, `features` BLOB, " +
                    "`device_salt` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`time_millis`, `device_salt`))",
                columns = "`time_millis`, `kind`, `heart_rate`, `movement`, `activity`, `offset_seconds`, `features`",
            )
            db.rebuild(
                table = "scale_weigh_ins",
                create = "(`scale_timestamp` INTEGER NOT NULL, `profile` INTEGER NOT NULL, `time_millis` INTEGER NOT NULL, " +
                    "`weight_kg` REAL, `heart_rate_bpm` INTEGER, `impedance_low_ohm` REAL, `impedance_high_ohm` REAL, " +
                    "`updated_millis` INTEGER NOT NULL, `written_millis` INTEGER, `device_salt` TEXT NOT NULL DEFAULT '', " +
                    "PRIMARY KEY(`scale_timestamp`, `profile`, `device_salt`))",
                columns = "`scale_timestamp`, `profile`, `time_millis`, `weight_kg`, `heart_rate_bpm`, " +
                    "`impedance_low_ohm`, `impedance_high_ohm`, `updated_millis`, `written_millis`",
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)

    /** A key cannot change in place in SQLite: a new table, the rows copied, the old one dropped. */
    private fun SupportSQLiteDatabase.rebuild(table: String, create: String, columns: String) {
        execSQL("CREATE TABLE `${table}_new` $create")
        execSQL("INSERT INTO `${table}_new` ($columns) SELECT $columns FROM `$table`")
        execSQL("DROP TABLE `$table`")
        execSQL("ALTER TABLE `${table}_new` RENAME TO `$table`")
    }
}
