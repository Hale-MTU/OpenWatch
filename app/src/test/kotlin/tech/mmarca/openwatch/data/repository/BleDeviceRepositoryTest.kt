package tech.mmarca.openwatch.data.repository

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.devices.FakeSharedPreferences
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorCapability
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration

class BleDeviceRepositoryTest {

    /** One [FakeSharedPreferences] that survives across repository instances, so a second [newRepository] is the storage round-trip. */
    private val context: Context = mockk<Context>().also { context ->
        every { context.getSharedPreferences(any(), any()) } returns FakeSharedPreferences()
    }

    private fun newRepository() = BleDeviceRepository(context)

    private fun BleDeviceRepository.addStrap(): BleSensorDevice = addDevice(
        displayName = "Strap",
        address = "AA:BB:CC:DD:EE:FF",
        bluetoothName = "Strap",
        capabilities = setOf(BleSensorCapability.HEART_RATE),
    )

    @Test
    fun `a wheel circumference below the default survives storage unchanged`() {
        newRepository().addDevice(
            displayName = "Speed",
            address = "AA:BB:CC:DD:EE:03",
            bluetoothName = null,
            capabilities = setOf(BleSensorCapability.CYCLING_SPEED_DISTANCE),
            wheelCircumferenceMm = 2000,
        )

        assertEquals(2000, newRepository().devices.single().wheelCircumferenceMm)
    }

    @Test
    fun `a wheel circumference outside the range is clamped to the nearest limit`() {
        val repo = newRepository()
        val tooSmall = repo.addDevice(
            displayName = "Small",
            address = "AA:BB:CC:DD:EE:03",
            bluetoothName = null,
            capabilities = setOf(BleSensorCapability.CYCLING_SPEED_DISTANCE),
            wheelCircumferenceMm = 100,
        )
        val tooLarge = repo.addDevice(
            displayName = "Large",
            address = "AA:BB:CC:DD:EE:04",
            bluetoothName = null,
            capabilities = setOf(BleSensorCapability.CYCLING_CADENCE),
            wheelCircumferenceMm = 9000,
        )

        assertEquals(BleSensorDevice.MinWheelCircumferenceMm, tooSmall.wheelCircumferenceMm)
        assertEquals(BleSensorDevice.MaxWheelCircumferenceMm, tooLarge.wheelCircumferenceMm)
    }

    @Test
    fun `updateBatteryLevel stores a changed value`() {
        val repo = newRepository()
        val device = repo.addStrap()

        repo.updateBatteryLevel(device.id, 80)

        val stored = repo.devices.single()
        assertEquals(80, stored.batteryPercent)
        assertNotNull(stored.batteryUpdatedAt)
    }

    @Test
    fun `updateBatteryLevel clamps out-of-range values`() {
        val repo = newRepository()
        val device = repo.addStrap()

        repo.updateBatteryLevel(device.id, 150)
        assertEquals(100, repo.devices.single().batteryPercent)

        repo.updateBatteryLevel(device.id, -10)
        assertEquals(0, repo.devices.single().batteryPercent)
    }

    @Test
    fun `an identical battery reading does not re-persist or advance the stamp`() {
        val repo = newRepository()
        val device = repo.addStrap()
        repo.updateBatteryLevel(device.id, 75)
        val firstStamp = repo.devices.single().batteryUpdatedAt
        val firstPublished = repo.devicesFlow.value

        repo.updateBatteryLevel(device.id, 75)

        // A repeated identical read is a no-op, and the stamp still says when the level last moved.
        assertEquals(firstStamp, repo.devices.single().batteryUpdatedAt)
        assertSame(firstPublished, repo.devicesFlow.value)
    }

    @Test
    fun `updateBatteryLevel ignores an unknown device id`() {
        val repo = newRepository()
        repo.addStrap()

        repo.updateBatteryLevel("does-not-exist", 50)

        assertNull(repo.devices.single().batteryPercent)
    }

    @Test
    fun `markSynced ignores an unknown device id`() {
        val repo = newRepository()
        repo.addStrap()

        // A sync can outlive the user forgetting the watch; that must not throw.
        repo.markSynced("does-not-exist", Instant.parse("2026-01-01T00:00:00Z"))

        assertNull(repo.devices.single().lastSyncedAt)
    }

    @Test
    fun `re-adding the same MAC updates the existing sensor`() {
        val repo = newRepository()
        val first = repo.addStrap()

        val second = repo.addDevice(
            displayName = "My strap",
            address = "aa:bb:cc:dd:ee:ff",
            bluetoothName = "TICKR",
            capabilities = setOf(BleSensorCapability.HEART_RATE, BleSensorCapability.CYCLING_CADENCE),
        )

        assertEquals(first.id, second.id)
        assertEquals(1, repo.devices.size)
        assertEquals("My strap", second.displayName)
        assertEquals("TICKR", second.bluetoothName)
        assertEquals(
            setOf(BleSensorCapability.HEART_RATE, BleSensorCapability.CYCLING_CADENCE),
            second.capabilities,
        )
    }

    @Test
    fun `the watch path does not clear what the sensors path added`() {
        val repo = newRepository()
        repo.addDevice(
            displayName = "Galaxy Watch8",
            address = "A8:D1:62:BE:3A:3B",
            bluetoothName = "Galaxy Watch8",
            capabilities = setOf(BleSensorCapability.HEART_RATE),
        )
        // The reverse order: onboarding as a watch must not wipe what was stored before.
        repo.addDevice(
            displayName = "Galaxy Watch8",
            address = "A8:D1:62:BE:3A:3B",
            bluetoothName = "Galaxy Watch8",
            capabilities = emptySet(),
            kind = BleDeviceKind.WATCH,
            integration = DeviceIntegration.WEAROS,
        )

        val stored = repo.devices.single()
        assertEquals(setOf(BleSensorCapability.HEART_RATE), stored.capabilities)
        assertEquals(BleDeviceKind.WATCH, stored.kind)
        assertTrue(stored.isWearosWatch)
    }

    @Test
    fun `kind, integration and lastSyncedAt survive a storage round-trip`() {
        val repo = newRepository()
        val watch = repo.addDevice(
            displayName = "vívoactive 5",
            address = "E0:48:24:D5:F7:10",
            bluetoothName = "vívoactive 5",
            capabilities = emptySet(),
            kind = BleDeviceKind.WATCH,
        )
        repo.addDevice(
            displayName = "Galaxy Watch8",
            address = "A8:D1:62:BE:3A:3B",
            bluetoothName = "Galaxy Watch8",
            capabilities = setOf(BleSensorCapability.HEART_RATE),
            kind = BleDeviceKind.WATCH,
            integration = DeviceIntegration.WEAROS,
        )
        val syncedAt = Instant.parse("2026-07-21T09:30:00Z")
        repo.markSynced(watch.id, syncedAt)

        // A second repository over the same prefs is the round-trip.
        val reloaded = newRepository().devices

        assertEquals(
            listOf(
                Triple(BleDeviceKind.WATCH, null, syncedAt),
                Triple(BleDeviceKind.WATCH, DeviceIntegration.WEAROS, null),
            ),
            reloaded.map { Triple(it.kind, it.integration, it.lastSyncedAt) },
        )
    }

    @Test
    fun registryJson_oldJsonWithoutWatchFieldsRoundTripsAsSensor() {
        // The shape the Kotlin build wrote before watches existed: no kind, integration or lastSyncedAt keys.
        val oldJson = """
            [{"id":"hr","displayName":"HR Strap","address":"AA:BB:CC:DD:EE:01",
              "bluetoothName":"HRM","capabilities":["HEART_RATE"],"enabled":true,
              "wheelCircumferenceMm":null,"batteryPercent":80,
              "batteryUpdatedAt":1700000000000,"addedAt":1690000000000}]
        """.trimIndent()

        val decoded = BleDeviceRegistryJson.decode(oldJson)

        assertEquals(1, decoded.size)
        val device = decoded.single()
        assertEquals(BleDeviceKind.SENSOR, device.kind)
        assertNull(device.integration)
        assertNull(device.lastSyncedAt)
        assertEquals(setOf(BleSensorCapability.HEART_RATE), device.capabilities)

        // And it round-trips.
        assertEquals(decoded, BleDeviceRegistryJson.decode(BleDeviceRegistryJson.encode(decoded)))
    }

    @Test
    fun registryJson_decodesADroppedIntegrationAsNone() {
        // What the Flutter build wrote for an onboarded Garmin watch. Garmin is gone: the watch keeps its kind and
        // loses the integration, so nothing claims it.
        val flutterJson = """
            [{"id":"ble-1753000000000000","displayName":"vívoactive 5",
              "address":"AA:BB:CC:DD:EE:05","bluetoothName":"vívoactive 5",
              "capabilities":[],"enabled":true,"wheelCircumferenceMm":null,
              "batteryPercent":null,"batteryUpdatedAt":null,
              "addedAt":1753000000000,"kind":"WATCH","integration":"GARMIN",
              "lastSyncedAt":1753100000000}]
        """.trimIndent()

        val decoded = BleDeviceRegistryJson.decode(flutterJson)

        assertEquals(1, decoded.size)
        val device = decoded.single()
        assertEquals(BleDeviceKind.WATCH, device.kind)
        assertNull(device.integration)
        assertEquals(Instant.ofEpochMilli(1_753_100_000_000), device.lastSyncedAt)
        assertTrue(device.isWatch)
        assertFalse(device.isWearosWatch)
    }

    @Test
    fun registryJson_unknownKindDegradesToSensorInsteadOfCrashing() {
        val futureJson = """
            [{"id":"x","displayName":"Future Device","address":"AA:BB:CC:DD:EE:09",
              "bluetoothName":null,"capabilities":[],"enabled":true,
              "wheelCircumferenceMm":null,"addedAt":1753000000000,
              "kind":"HOLOGRAPH","integration":"NEURALINK"}]
        """.trimIndent()

        val decoded = BleDeviceRegistryJson.decode(futureJson)

        assertEquals(1, decoded.size)
        assertEquals(BleDeviceKind.SENSOR, decoded.single().kind)
        assertNull(decoded.single().integration)
    }
}
