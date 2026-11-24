package tech.mmarca.openwatch.healthconnect

import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What Health Connect does with a record whose `clientRecordId` it already holds.
 *
 * Measured on a Pixel 6 Pro, Android 17 (2026-11-21): no write throws; an equal version
 * replaces the record, a lower one is silently ignored, a higher one replaces. So on the
 * platform's Health Connect (Android 14 and newer) the "already present" refusal the write
 * helpers fall back on never happens, and a record rewritten at the same version changes.
 * The Health Connect APK on Android 13 and older is not covered here.
 *
 * Grants the app under test WRITE_STEPS itself, as `adb shell pm grant` would, so it needs no
 * step by hand: on CI's emulator the app is a new install every run.
 */
@RunWith(AndroidJUnit4::class)
class ClientRecordVersionTest {

    private val client = HealthConnectClient.getOrCreate(InstrumentationRegistry.getInstrumentation().targetContext)
    private val start = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES)
    private val id = "version-probe-${System.nanoTime()}"

    @Before
    fun grantWriteSteps(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, WRITE_STEPS)
        // Health Connect learns of the grant a moment later; a write before then is refused.
        val granted = withTimeoutOrNull(GRANT_WAIT_MILLIS) {
            while (WRITE_STEPS !in client.permissionController.getGrantedPermissions()) delay(GRANT_POLL_MILLIS)
            true
        }
        assertTrue("Health Connect never saw the WRITE_STEPS grant", granted == true)
    }

    private fun minute(count: Long, version: Long) = StepsRecord(
        startTime = start,
        startZoneOffset = ZoneOffset.UTC,
        endTime = start.plusSeconds(60),
        endZoneOffset = ZoneOffset.UTC,
        count = count,
        metadata = Metadata.manualEntry(clientRecordId = id, clientRecordVersion = version),
    )

    private suspend fun stored(): List<Long> =
        client.readRecords(
            // Its own records only: the app may read those without READ_STEPS.
            ReadRecordsRequest(
                StepsRecord::class,
                TimeRangeFilter.between(start, start.plusSeconds(60)),
                dataOriginFilter = setOf(DataOrigin(InstrumentationRegistry.getInstrumentation().targetContext.packageName)),
            ),
        ).records.filter { it.metadata.clientRecordId == id }.map { it.count }

    private suspend fun attempt(count: Long, version: Long): String =
        runCatching { client.insertRecords(listOf(minute(count, version))) }
            .fold({ "accepted" }, { "threw ${it::class.simpleName}" })

    @Test
    fun probe(): Unit = runBlocking {
        try {
            val first = attempt(10, version = 5)
            val equal = attempt(20, version = 5)
            val afterEqual = stored()
            val lower = attempt(30, version = 4)
            val afterLower = stored()
            val higher = attempt(40, version = 6)
            val afterHigher = stored()
            Log.i(
                TAG,
                "first=$first equal=$equal -> $afterEqual lower=$lower -> $afterLower " +
                    "higher=$higher -> $afterHigher sdk=${android.os.Build.VERSION.SDK_INT}",
            )
            assumeTrue("the platform's Health Connect", android.os.Build.VERSION.SDK_INT >= 34)
            assertEquals(listOf("accepted", "accepted", "accepted", "accepted"), listOf(first, equal, lower, higher))
            assertEquals("an equal version replaces", listOf(20L), afterEqual)
            assertEquals("a lower version is ignored", listOf(20L), afterLower)
            assertEquals("a higher version replaces", listOf(40L), afterHigher)
        } finally {
            // Cleaning up must not hide what went wrong above.
            runCatching { client.deleteRecords(StepsRecord::class, emptyList(), listOf(id)) }
        }
    }

    private companion object {
        const val TAG = "VersionProbe"
        const val WRITE_STEPS = "android.permission.health.WRITE_STEPS"
        const val GRANT_WAIT_MILLIS = 15_000L
        const val GRANT_POLL_MILLIS = 250L
    }
}
