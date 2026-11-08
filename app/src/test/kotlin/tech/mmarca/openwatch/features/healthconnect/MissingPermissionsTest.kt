package tech.mmarca.openwatch.features.healthconnect

import org.junit.Assert.assertEquals
import org.junit.Test

class MissingPermissionsTest {

    @Test
    fun `the missing ones list the data types first and the extra access last`() {
        val declared = setOf(
            "android.permission.health.READ_HEALTH_DATA_HISTORY",
            "android.permission.health.WRITE_STEPS",
            "android.permission.health.READ_HEART_RATE",
            "android.permission.health.WRITE_SLEEP",
        )
        val granted = setOf("android.permission.health.WRITE_SLEEP")

        assertEquals(
            listOf(
                "android.permission.health.READ_HEART_RATE",
                "android.permission.health.WRITE_STEPS",
                "android.permission.health.READ_HEALTH_DATA_HISTORY",
            ),
            missingPermissions(declared, granted),
        )
    }

    @Test
    fun `a permission without a platform label still reads as words`() {
        assertEquals("Read health data in background", readablePermission("android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"))
    }
}
