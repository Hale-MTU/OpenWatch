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
 * Every Room version this app has shipped opens as the current one, with its rows kept.
 *
 * The database is at version 1, so today this only proves the harness: the exported schema
 * builds a database the current code opens. The first `MIGRATION_1_2` adds its case here, a
 * version-1 database with rows in every table, migrated and read back.
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
        ).allowMainThreadQueries().build()
        val count = database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM scale_weigh_ins").use { it.moveToFirst(); it.getInt(0) }
        database.close()

        assertEquals(1, count)
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
