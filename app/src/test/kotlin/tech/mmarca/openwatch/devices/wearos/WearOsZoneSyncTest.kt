package tech.mmarca.openwatch.devices.wearos

import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.mmarca.openwatch.domain.insights.ageMaxHeartRate
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration
import tech.mmarca.openwatch.domain.preferences.BodyProfile
import tech.mmarca.openwatch.healthconnect.HealthConnectGateway

class WearOsZoneSyncTest {

    private val sent = linkedMapOf<String, String>()
    private val nodePort = mockk<WearOsNodePort> {
        coEvery { setWatchSetting(any(), any(), any(), any()) } answers {
            sent[thirdArg()] = arg(3)
            true
        }
    }
    private val healthConnect = mockk<HealthConnectGateway> {
        coEvery { readMaxHeartRate(any(), any()) } returns null
        coEvery { forEachSyncRecordPage(any(), any(), any(), any()) } returns Unit
    }
    private val retry = object : WearOsNightSeriesSync.Retry {
        override suspend fun <T> run(pull: suspend () -> T): T = pull()
    }
    private val now = Instant.parse("2026-06-01T12:00:00Z")

    /** The age-predicted maximum at 36, the profile's age on that day. */
    private val predicted = ageMaxHeartRate(36, null).toInt().toString()

    private fun sync(energyProfile: suspend () -> BodyProfile) = WearOsZoneSync(
        healthConnect = healthConnect,
        nodePort = nodePort,
        profile = { BodyProfile(birthYear = 1990) },
        energyProfile = energyProfile,
        clock = { now },
        zone = { ZoneOffset.UTC },
    )

    @Test
    fun `the zones and the profile go to the watch together`() = runTest {
        sync { BodyProfile(weightKg = 70.0, birthYear = 1990) }.sync(WATCH, retry)

        assertEquals("700", sent["body_hg"])
        assertEquals("1990", sent["birth_year"])
        assertEquals(predicted, sent["hr_max"])
    }

    @Test
    fun `a weight that cannot be read still sends the zones`() = runTest {
        sync { throw SecurityException("no weight grant") }.sync(WATCH, retry)

        assertEquals(setOf("hr_max", "hr_rest"), sent.keys)
        assertEquals(predicted, sent["hr_max"])
    }

    private companion object {
        val WATCH = BleSensorDevice(
            id = "watch-1",
            displayName = "Galaxy Watch8",
            address = "AA:BB:CC:DD:EE:FF",
            bluetoothName = "Galaxy Watch8 (89FZ)",
            capabilities = emptySet(),
            enabled = true,
            wheelCircumferenceMm = null,
            addedAt = Instant.parse("2026-01-01T00:00:00Z"),
            kind = BleDeviceKind.WATCH,
            integration = DeviceIntegration.WEAROS,
        )
    }
}
