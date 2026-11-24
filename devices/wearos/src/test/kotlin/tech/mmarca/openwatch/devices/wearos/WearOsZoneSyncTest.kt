package tech.mmarca.openwatch.devices.wearos

import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.mmarca.healthcalculations.insights.ageMaxHeartRate
import tech.mmarca.healthcalculations.preferences.BiologicalSex
import tech.mmarca.healthcalculations.preferences.BodyProfile
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration
import tech.mmarca.openwatch.healthconnect.HealthConnectReads

class WearOsZoneSyncTest {

    private val sent = linkedMapOf<String, String>()
    private val nodePort = mockk<WearOsNodePort> {
        coEvery { setWatchSetting(any(), any(), any(), any()) } answers {
            sent[thirdArg()] = arg(3)
            true
        }
    }
    private val healthConnect = mockk<HealthConnectReads> {
        coEvery { readMaxHeartRate(any(), any()) } returns null
        coEvery { forEachSyncRecordPage(any(), any(), any(), any()) } returns Unit
    }
    private val retry = object : WearOsLinkRetry {
        override suspend fun <T> run(pull: suspend () -> T): T = pull()
    }
    private val now = Instant.parse("2026-06-01T12:00:00Z")

    /** The age-predicted maximum at 36, the profile's age on that day. */
    private val predicted = ageMaxHeartRate(36, null).toInt().toString()

    private fun sync(bodyProfile: suspend () -> BodyProfile) = WearOsZoneSync(
        healthConnect = healthConnect,
        nodePort = nodePort,
        profile = { BodyProfile(birthYear = 1990) },
        bodyProfile = bodyProfile,
        clock = { now },
        zone = { ZoneOffset.UTC },
    )

    @Test
    fun `the zones and the profile go to the watch together`() = runTest {
        sync { BodyProfile(weightKg = 70.24, heightCm = 178.4, birthYear = 1990, sex = BiologicalSex.FEMALE) }.sync(WATCH, retry)

        assertEquals(
            mapOf(
                "hr_max" to predicted, "hr_rest" to "0",
                "body_hg" to "702", "body_cm" to "178", "birth_year" to "1990", "sex" to "f",
            ),
            sent,
        )
    }

    @Test
    fun `a weight that cannot be read still sends the zones`() = runTest {
        sync { throw SecurityException("no weight grant") }.sync(WATCH, retry)

        assertEquals(setOf("hr_max", "hr_rest"), sent.keys)
        assertEquals(predicted, sent["hr_max"])
    }

    @Test
    fun `a profile without a value, or with one the watch does not take, sends none for it`() {
        val settings = WearOsZoneSync.profileSettings(BodyProfile(weightKg = 12.0, heightCm = null, birthYear = null, sex = null)).toMap()

        assertEquals(mapOf("body_hg" to "0", "body_cm" to "0", "birth_year" to "0", "sex" to "0"), settings)
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
