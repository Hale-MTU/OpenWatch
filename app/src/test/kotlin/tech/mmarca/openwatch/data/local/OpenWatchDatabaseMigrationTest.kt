package tech.mmarca.openwatch.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every Room version this app has shipped opens as the current one, with its rows kept. Each
 * migration has its case: a database of the older version with rows in every table, migrated,
 * checked against the exported schema, and read back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenWatchDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OpenWatchDatabase::class.java,
    )

    @Test
    fun `version 1 becomes 2 with every row kept as the first device's`() {
        helper.createDatabase(DB, 1).apply {
            execSQL("INSERT INTO garmin_wellness_samples (metric, time_millis, value) VALUES ('stress', 1700000000000, 42)")
            execSQL(
                "INSERT INTO garmin_sleep_minutes (time_millis, kind, heart_rate, offset_seconds) " +
                    "VALUES (1700000060000, 'raw', 52.0, 3600)",
            )
            execSQL(
                "INSERT INTO scale_weigh_ins (scale_timestamp, profile, time_millis, weight_kg, updated_millis) " +
                    "VALUES (1700000000, 1, 1700000000000, 71.5, 1700000000000)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 2, true, OpenWatchMigrations.MIGRATION_1_2)

        fun row(sql: String): List<String> = db.query(sql).use { cursor ->
            check(cursor.moveToFirst()) { "no row: $sql" }
            (0 until cursor.columnCount).map { cursor.getString(it) ?: "null" }
        }
        assertEquals(listOf("stress", "1700000000000", "42", ""), row("SELECT metric, time_millis, value, device_salt FROM garmin_wellness_samples"))
        assertEquals(listOf("1700000060000", "52", ""), row("SELECT time_millis, heart_rate, device_salt FROM garmin_sleep_minutes"))
        assertEquals(listOf("1700000000", "71.5", ""), row("SELECT scale_timestamp, weight_kg, device_salt FROM scale_weigh_ins"))
        // The registry and the per-device state start empty; the registry imports its JSON itself.
        assertEquals(listOf("0"), row("SELECT COUNT(*) FROM devices"))
        assertEquals(listOf("0"), row("SELECT COUNT(*) FROM device_state"))
        db.close()
    }

    @Test
    fun `a version-1 database opens with the current code`() {
        helper.createDatabase(DB, 1).apply {
            execSQL(
                "INSERT INTO scale_weigh_ins (scale_timestamp, profile, time_millis, updated_millis) " +
                    "VALUES (1700000000, 1, 1700000000000, 1700000000000)",
            )
            close()
        }

        val database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OpenWatchDatabase::class.java,
            DB,
        ).addMigrations(*OpenWatchMigrations.ALL).allowMainThreadQueries().build()
        val count = database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM scale_weigh_ins").use { it.moveToFirst(); it.getInt(0) }
        database.close()

        assertEquals(1, count)
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
