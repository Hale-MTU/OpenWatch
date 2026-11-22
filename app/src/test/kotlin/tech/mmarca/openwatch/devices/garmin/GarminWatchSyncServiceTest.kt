package tech.mmarca.openwatch.devices.garmin

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.SystemClock
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.mmarca.openwatch.data.repository.testDeviceStateStore
import tech.mmarca.openwatch.devices.core.RadioLeaseBusyException
import tech.mmarca.openwatch.healthconnect.HealthConnectSyncDisabledException
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.devices.FakeSharedPreferences
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncProgress
import tech.mmarca.openwatch.devices.core.sync.SyncTrigger
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncResult
import tech.mmarca.openwatch.devices.core.sync.SyncFailure
import tech.mmarca.openwatch.devices.core.sync.HealthSeries
import tech.mmarca.openwatch.devices.core.sync.SkipReason
import tech.mmarca.openwatch.devices.core.sync.SkippedSeries
import tech.mmarca.openwatch.devices.core.sync.SyncTally
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope

/**
 * What a sync does around the radio. The watch is a fake here: the order, the refusals and
 * what each failure tells the user are the part that has gone wrong before.
 */
class GarminWatchSyncServiceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context = mockk<Context>(relaxed = true)
    private val deviceRepository = mockk<BleDeviceRepository>(relaxed = true)
    private val stateStore = GarminDeviceStateStore(testDeviceStateStore(), FakeSharedPreferences())
    private val downloadImport = mockk<GarminDownloadImport>()
    private val fileStore by lazy { GarminFileStore(resolveDirectory = { temp.root }) }

    private var pullResult: GarminPullResult = GarminPullResult(files = emptyList())
    private var pullFailure: Throwable? = null
    private var pulls = 0
    private var heldFor: Duration? = null

    private val radio = object : GarminRadio {
        override suspend fun pull(
            device: BleSensorDevice,
            listenAfter: Duration,
            onProgress: ((DeviceSyncProgress) -> Unit)?,
        ): GarminPullResult {
            pulls++
            heldFor = listenAfter
            pullFailure?.let { throw it }
            return pullResult
        }

        override suspend fun find(address: String, timeout: Duration, cancelled: CompletableDeferred<Unit>?) = true

        override suspend fun upload(
            device: BleSensorDevice,
            type: GarminUploadFileType,
            bytes: ByteArray,
            onStage: (GarminSendStage) -> Unit,
        ): GarminSendFileResult = GarminSendFileResult.Sent
    }

    @Before
    fun setUp() {
        // The widget refresh after a sync asks the platform what is placed.
        mockkStatic(AppWidgetManager::class)
        every { AppWidgetManager.getInstance(any()) } returns null
        // The radio lease times its holder.
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        coEvery { downloadImport.import(any(), any()) } returns GarminActivityImportResult(written = 1)
    }

    @After
    fun tearDown() {
        unmockkStatic(AppWidgetManager::class)
        unmockkStatic(SystemClock::class)
    }

    private fun service() = GarminWatchSyncService(
        appScope = CoroutineScope(SupervisorJob()),
        radioLeases = tech.mmarca.openwatch.devices.core.RadioLeaseRegistry(),
        context = context,
        bleDeviceRepository = deviceRepository,
        stateStore = stateStore,
        downloadImport = downloadImport,
        fileStore = fileStore,
        radio = radio,
    )

    @Test
    fun `files that landed are imported, then the watch is stamped`() = runTest {
        pullResult = GarminPullResult(files = listOf(file(1), file(2)))

        val result = service().sync(WATCH, SyncTrigger.BACKGROUND, null)

        assertEquals(DeviceSyncResult.Succeeded(written = mapOf(SyncTally.FILES to 2)), result)
        coVerify(exactly = 1) { downloadImport.import(WATCH.id, pullResult.files) }
        // Stamped last: a sync that failed to import must come back for the same files.
        coVerify(exactly = 1) { deviceRepository.markSynced(WATCH.id, any()) }
    }

    @Test
    fun `a requested sync holds the link for the watch's errands, a background one does not`() = runTest {
        service().sync(WATCH, SyncTrigger.REQUESTED, null)
        assertEquals(20.seconds, heldFor)

        service().sync(WATCH, SyncTrigger.BACKGROUND, null)
        assertEquals(Duration.ZERO, heldFor)
    }

    @Test
    fun `a watch out of range fails the sync and stamps nothing`() = runTest {
        pullFailure = GarminGattClientException("out of range")

        val result = service().sync(WATCH, SyncTrigger.BACKGROUND, null)

        assertEquals(DeviceSyncResult.Failed(SyncFailure.Unreachable), result)
        coVerify(exactly = 0) { downloadImport.import(any(), any()) }
        coVerify(exactly = 0) { deviceRepository.markSynced(any(), any()) }
    }

    @Test
    fun `a failed import leaves the watch unstamped, so the next sync fetches again`() = runTest {
        pullResult = GarminPullResult(files = listOf(file(1)))
        coEvery { downloadImport.import(any(), any()) } throws IllegalStateException("Health Connect is down")

        val result = service().sync(WATCH, SyncTrigger.BACKGROUND, null)

        assertTrue((result as DeviceSyncResult.Failed).failure is SyncFailure.Unknown)
        coVerify(exactly = 0) { deviceRepository.markSynced(any(), any()) }
    }

    @Test
    fun `wellness types left out for want of a grant are reported, and the sync still succeeds`() = runTest {
        pullResult = GarminPullResult(files = listOf(file(1)))
        coEvery { downloadImport.import(any(), any()) } returns
            GarminActivityImportResult(written = 1, wellnessWithheldPermissions = setOf(WriteSleep))

        val result = service().sync(WATCH, SyncTrigger.BACKGROUND, null)

        assertEquals(
            setOf(SkippedSeries(HealthSeries.RECORDS, SkipReason.PERMISSION, setOf(WriteSleep))),
            (result as DeviceSyncResult.Succeeded).skipped,
        )
        coVerify(exactly = 1) { deviceRepository.markSynced(WATCH.id, any()) }
    }

    @Test
    fun `a write refused for a missing permission is a permission failure, not a sentence`() = runTest {
        pullResult = GarminPullResult(files = listOf(file(1)))
        coEvery { downloadImport.import(any(), any()) } throws SecurityException("WRITE_WEIGHT not granted")

        val result = service().sync(WATCH, SyncTrigger.BACKGROUND, null)

        assertEquals(DeviceSyncResult.Failed(SyncFailure.HealthConnectPermission), result)
    }

    @Test
    fun `a paused Health Connect sync is said as paused, not as the exception's text`() = runTest {
        pullResult = GarminPullResult(files = listOf(file(1)))
        coEvery { downloadImport.import(any(), any()) } throws HealthConnectSyncDisabledException()

        val result = service().sync(WATCH, SyncTrigger.BACKGROUND, null)

        assertEquals(DeviceSyncResult.Failed(SyncFailure.SyncPaused), result)
    }

    @Test
    fun `a radio another task holds is busy, which the schedule retries`() = runTest {
        pullFailure = RadioLeaseBusyException(holder = "notifications")

        val result = service().sync(WATCH, SyncTrigger.BACKGROUND, null) as DeviceSyncResult.Failed

        assertEquals(SyncFailure.RadioBusy, result.failure)
        assertTrue(result.failure.transient)
    }

    @Test
    fun `an interrupted sync imports what landed and fails as interrupted`() = runTest {
        pullResult = GarminPullResult(files = listOf(file(1)), incompleteReason = "the link dropped")

        val result = service().sync(WATCH, SyncTrigger.BACKGROUND, null)

        assertEquals(
            DeviceSyncResult.Failed(SyncFailure.Interrupted, partial = DeviceSyncResult.Succeeded(written = mapOf(SyncTally.FILES to 1))),
            result,
        )
        // The files did land, so they are imported before the failure is reported.
        coVerify(exactly = 1) { downloadImport.import(WATCH.id, pullResult.files) }
    }

    @Test
    fun `an interrupted sync that landed nothing reports the interruption, not the link's own words`() = runTest {
        pullResult = GarminPullResult(files = emptyList(), incompleteReason = "the watch went away")

        val result = service().sync(WATCH, SyncTrigger.BACKGROUND, null)

        assertEquals(DeviceSyncResult.Failed(SyncFailure.Interrupted), result)
    }

    @Test
    fun `workouts waiting for a grant are named with the grants they need, not hidden behind Synced`() = runTest {
        pullResult = GarminPullResult(files = listOf(file(1)))
        coEvery { downloadImport.import(any(), any()) } returns GarminActivityImportResult(
            written = 0,
            missingPermission = listOf(file(1)),
            missingWritePermissions = setOf(WriteExercise),
        )

        val result = service().sync(WATCH, SyncTrigger.BACKGROUND, null) as DeviceSyncResult.Succeeded

        assertEquals(setOf(SkippedSeries(HealthSeries.WORKOUTS, SkipReason.PERMISSION, setOf(WriteExercise))), result.skipped)
        assertEquals(setOf(WriteExercise), result.withheldPermissions)
    }

    @Test
    fun `workouts Health Connect would not take fail the sync, with what did land`() = runTest {
        pullResult = GarminPullResult(files = listOf(file(1), file(2)))
        coEvery { downloadImport.import(any(), any()) } returns
            GarminActivityImportResult(written = 1, failedWrites = listOf(file(2)))

        val result = service().sync(WATCH, SyncTrigger.BACKGROUND, null)

        assertEquals(
            DeviceSyncResult.Failed(SyncFailure.NotSaved, partial = DeviceSyncResult.Succeeded(written = mapOf(SyncTally.FILES to 2))),
            result,
        )
    }

    @Test
    fun `a workout without calories for want of a weight is said, not guessed`() = runTest {
        pullResult = GarminPullResult(files = listOf(file(1)))
        coEvery { downloadImport.import(any(), any()) } returns
            GarminActivityImportResult(written = 1, withoutCaloriesForWantOfWeight = 1)

        val result = service().sync(WATCH, SyncTrigger.BACKGROUND, null) as DeviceSyncResult.Succeeded

        assertEquals(setOf(SkippedSeries(HealthSeries.WORKOUT_ENERGY, SkipReason.NO_BODY_WEIGHT)), result.skipped)
    }

    @Test
    fun `files an earlier run saved but never imported go in before the pull`() = runTest {
        val pending = file(9)
        fileStore.save(pending, now = Instant.parse("2026-07-22T10:00:00Z"), deviceId = WATCH.id)

        service().sync(WATCH, SyncTrigger.BACKGROUND, null)

        // Their keys must be recorded before the listing is filtered, or they are fetched twice.
        coVerify(exactly = 1) { downloadImport.import(WATCH.id, match { it.size == 1 }) }
        assertEquals(1, pulls)
    }

    @Test
    fun `a pending import that fails stops the sync before the radio opens`() = runTest {
        fileStore.save(file(9), now = Instant.parse("2026-07-22T10:00:00Z"), deviceId = WATCH.id)
        coEvery { downloadImport.import(any(), any()) } throws IllegalStateException("Health Connect is down")

        val result = service().sync(WATCH, SyncTrigger.BACKGROUND, null)

        assertTrue((result as DeviceSyncResult.Failed).failure is SyncFailure.Unknown)
        assertEquals("pulling more would only queue more behind a write path that is down", 0, pulls)
    }

    private fun file(index: Int) = GarminDownloadedFile(
        entry = GarminDirectoryEntry(
            fileIndex = index,
            type = GarminFileType.ACTIVITY,
            fileNumber = index,
            specificFlags = 0,
            fileFlags = 0,
            fileSize = 3,
            fileDate = Instant.parse("2026-07-22T10:00:00Z"),
        ),
        bytes = byteArrayOf(1, 2, 3),
    )

    private companion object {
        val WATCH = BleSensorDevice(
            id = "watch-1",
            displayName = "Forerunner",
            address = "AA:BB:CC:DD:EE:FF",
            bluetoothName = "Forerunner 255",
            capabilities = emptySet(),
            enabled = true,
            wheelCircumferenceMm = null,
            addedAt = Instant.parse("2026-07-01T10:00:00Z"),
            kind = BleDeviceKind.WATCH,
        )
    }
}

private const val WriteSleep = "android.permission.health.WRITE_SLEEP"
private const val WriteExercise = "android.permission.health.WRITE_EXERCISE"
