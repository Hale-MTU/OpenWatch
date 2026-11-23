package tech.mmarca.openwatch.data.repository

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
import tech.mmarca.openwatch.devices.garmin.isGarmin
import tech.mmarca.openwatch.devices.garmin.isGarminWatch

class BleDeviceRepositoryTest {

    /** One table that survives across repository instances, so a second [newRepository] is the storage round-trip. */
    private val dao = FakeDeviceDao()

    private fun newRepository() = testDeviceRepository(dao)

    private fun BleDeviceRepository.addStrap(): BleSensorDevice = addDevice(
        displayName = "Strap",
        address = "AA:BB:CC:DD:EE:FF",
        bluetoothName = "Strap",
        capabilities = setOf(BleSensorCapability.HEART_RATE),
    )

    @Test
    fun resolveAssignments_usesFirstEnabledDevicePerCapability() {
        val repo = newRepository()
        val hr = repo.addStrap()
        val power = repo.addDevice(
            displayName = "Power",
            address = "AA:BB:CC:DD:EE:02",
            bluetoothName = null,
            capabilities = setOf(BleSensorCapability.CYCLING_POWER),
        )

        val assignments = repo.resolveCapabilityAssignments()

        assertEquals(hr.id, assignments[BleSensorCapability.HEART_RATE]?.id)
        assertEquals(power.id, assignments[BleSensorCapability.CYCLING_POWER]?.id)
    }

    @Test
    fun disabledDevicesAreExcludedFromAssignments() {
        val repo = newRepository()
        val strap = repo.addStrap()
        repo.setDeviceEnabled(strap.id, enabled = false)

        assertEquals(0, repo.resolveCapabilityAssignments().size)
    }

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
        val firstPublished = repo.devices

        repo.updateBatteryLevel(device.id, 75)

        // A repeated identical read is a no-op, and the stamp still says when the level last moved.
        assertEquals(firstStamp, repo.devices.single().batteryUpdatedAt)
        assertSame(firstPublished, repo.devices)
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
    fun `a sync-only watch is kept out of capability assignment`() {
        val repo = newRepository()
        val sensor = repo.addStrap()
        repo.addDevice(
            displayName = "vívoactive 5",
            address = "E0:48:24:D5:F7:10",
            bluetoothName = "vívoactive 5",
            // The watch path registers no capabilities, so nothing is handed to the recording coordinator.
            capabilities = emptySet(),
            kind = BleDeviceKind.WATCH,
        )

        val assignments = repo.resolveCapabilityAssignments()

        assertEquals(sensor.id, assignments[BleSensorCapability.HEART_RATE]?.id)
        assertFalse(assignments.values.any { it.isWatch })
    }

    @Test
    fun `a watch also added through the sensors path DOES take part`() {
        val repo = newRepository()
        val watch = repo.addDevice(
            displayName = "vívoactive 5",
            address = "E0:48:24:D5:F7:10",
            bluetoothName = "vívoactive 5",
            capabilities = emptySet(),
            kind = BleDeviceKind.WATCH,
            integration = DeviceIntegration.GARMIN,
        )
        // The Sensors path: capabilities and no kind. It never makes a watch stop being one.
        repo.addDevice(
            displayName = "vívoactive 5",
            address = "E0:48:24:D5:F7:10",
            bluetoothName = "vívoactive 5",
            capabilities = setOf(BleSensorCapability.HEART_RATE),
        )

        val stored = repo.devices.single()
        assertEquals(watch.id, stored.id)
        // Both roles on one entry, the way an Edge already was.
        assertEquals(BleDeviceKind.WATCH, stored.kind)
        assertTrue(stored.isGarmin)
        assertEquals(
            stored.id,
            repo.resolveCapabilityAssignments()[BleSensorCapability.HEART_RATE]?.id,
        )
    }

    @Test
    fun `the watch path does not clear what the sensors path added`() {
        val repo = newRepository()
        repo.addDevice(
            displayName = "vívoactive 5",
            address = "E0:48:24:D5:F7:10",
            bluetoothName = "vívoactive 5",
            capabilities = setOf(BleSensorCapability.HEART_RATE),
        )
        // The reverse order: onboarding as a watch must not wipe the live role.
        repo.addDevice(
            displayName = "vívoactive 5",
            address = "E0:48:24:D5:F7:10",
            bluetoothName = "vívoactive 5",
            capabilities = emptySet(),
            kind = BleDeviceKind.WATCH,
            integration = DeviceIntegration.GARMIN,
        )

        val stored = repo.devices.single()
        assertEquals(setOf(BleSensorCapability.HEART_RATE), stored.capabilities)
        assertEquals(BleDeviceKind.WATCH, stored.kind)
        assertTrue(stored.isLiveSensorCapable)
    }

    @Test
    fun `an Edge bike computer with capabilities DOES take part`() {
        val repo = newRepository()
        repo.addStrap()
        val edge = repo.addDevice(
            displayName = "Edge 840",
            address = "E0:48:24:D5:F7:20",
            bluetoothName = "Edge 840",
            capabilities = setOf(BleSensorCapability.CYCLING_POWER),
            kind = BleDeviceKind.BIKE_COMPUTER,
            integration = DeviceIntegration.GARMIN,
        )

        val assignments = repo.resolveCapabilityAssignments()

        // A bike computer broadcasting standard GATT is a live source.
        assertEquals(edge.id, assignments[BleSensorCapability.CYCLING_POWER]?.id)
    }

    @Test
    fun `a bike computer with NO capabilities stays out`() {
        val repo = newRepository()
        repo.addStrap()
        repo.addDevice(
            displayName = "Edge 840",
            address = "E0:48:24:D5:F7:20",
            bluetoothName = "Edge 840",
            capabilities = emptySet(),
            kind = BleDeviceKind.BIKE_COMPUTER,
            integration = DeviceIntegration.GARMIN,
        )

        val assignments = repo.resolveCapabilityAssignments()

        assertFalse(assignments.values.any { it.isBikeComputer })
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
            displayName = "Edge 840",
            address = "E0:48:24:D5:F7:20",
            bluetoothName = "Edge 840",
            capabilities = emptySet(),
            kind = BleDeviceKind.BIKE_COMPUTER,
            integration = DeviceIntegration.GARMIN,
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
                Triple(BleDeviceKind.BIKE_COMPUTER, DeviceIntegration.GARMIN, null),
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
    fun registryJson_decodesFlutterEraWatchJson() {
        // What the Flutter build wrote for an onboarded Garmin watch: storageName strings for kind/integration.
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
        assertEquals(DeviceIntegration.GARMIN, device.integration)
        assertEquals(Instant.ofEpochMilli(1_753_100_000_000), device.lastSyncedAt)
        assertTrue(device.isWatch)
        assertTrue(device.isGarminWatch)
        assertTrue(device.isGarmin)
    }

    @Test
    fun registryJson_unknownKindDegradesToSensorInsteadOfCrashing() {
        val futureJson = """
            [{"id":"x","displayName":"Future Device","address":"AA:BB:CC:DD:EE:09",
              "bluetoothName":null,"capabilities":[],"enabled":true,
              "wheelCircumferenceMm":null,"addedAt":1753000000000,
              "kind":"HOLOGRAPH"}]
        """.trimIndent()

        val decoded = BleDeviceRegistryJson.decode(futureJson)

        assertEquals(1, decoded.size)
        assertEquals(BleDeviceKind.SENSOR, decoded.single().kind)
        assertNull(decoded.single().integration)
    }

    @Test
    fun registryJson_oneUnreadableEntryForgetsNothingAndIsWrittenBack() {
        val good = """{"id":"w1","displayName":"Watch","address":"AA:BB:CC:DD:EE:01","addedAt":1000,"kind":"WATCH"}"""
        val broken = """{"id":"w2","displayName":"Lost","address":"AA:BB:CC:DD:EE:02"}"""

        val decoded = BleDeviceRegistryJson.decodeKeepingUnreadable("[$good,$broken]")!!

        assertEquals(listOf("w1"), decoded.devices.map { it.id })
        assertEquals(1, decoded.unreadable.size)
        val rewritten = BleDeviceRegistryJson.decodeKeepingUnreadable(
            BleDeviceRegistryJson.encode(decoded.devices, decoded.unreadable),
        )!!
        assertEquals(listOf("w1"), rewritten.devices.map { it.id })
        assertEquals("w2", org.json.JSONObject(rewritten.unreadable.single()).getString("id"))
    }

    @Test
    fun `the old JSON registry is imported once, and an entry it cannot read stays in the old file`() {
        val legacy = FakeSharedPreferences()
        val good = """{"id":"w1","displayName":"Watch","address":"AA:BB:CC:DD:EE:01","addedAt":1000,"kind":"WATCH"}"""
        val broken = """{"id":"w2","displayName":"Lost","address":"AA:BB:CC:DD:EE:02"}"""
        legacy.edit().putString("devices", "[$good,$broken]").apply()

        val repo = testDeviceRepository(dao, legacy)

        assertEquals(listOf("w1"), repo.devices.map { it.id })
        // A watch stored before salts keeps its ids: the empty salt.
        assertEquals("", repo.devices.single().idSalt)
        assertTrue(legacy.getString("devices", null)!!.contains("\"w2\""))
        // Once: a device removed after the import does not come back at the next start.
        repo.removeDevice("w1")
        assertTrue(testDeviceRepository(dao, legacy).devices.isEmpty())
    }

    @Test
    fun `a JSON registry that does not parse imports nothing and is left as it was`() {
        val legacy = FakeSharedPreferences()
        legacy.edit().putString("devices", "[{\"id\":").apply()

        val repo = testDeviceRepository(dao, legacy)

        assertTrue(repo.devices.isEmpty())
        assertEquals("[{\"id\":", legacy.getString("devices", null))
    }

    @Test
    fun `the first device of a family keeps no salt, and each after it gets one of its own`() {
        val repo = newRepository()
        fun watch(n: Int) = repo.addDevice(
            "Watch $n", "AA:BB:CC:DD:EE:0$n", null, emptySet(),
            kind = BleDeviceKind.WATCH, integration = DeviceIntegration.GARMIN,
        )

        val first = watch(1)
        val second = watch(2)
        val third = watch(3)
        val wearOs = repo.addDevice("Pixel", "AA:BB:CC:DD:EE:09", null, emptySet(), kind = BleDeviceKind.WATCH, integration = DeviceIntegration.WEAROS)

        assertEquals("", first.idSalt)
        assertTrue(second.idSalt.isNotEmpty() && third.idSalt.isNotEmpty() && second.idSalt != third.idSalt)
        // Another family's first device has none either.
        assertEquals("", wearOs.idSalt)
        // And the salts are stored.
        assertEquals(second.idSalt, newRepository().devices.single { it.id == second.id }.idSalt)
    }

    @Test
    fun `a row of an integration this version does not know stays in the table unread`() {
        val repo = newRepository()
        repo.addStrap()
        dao.upsert(repo.devices.single().toEntity().copy(id = "w9", integration = "SOMEVENDOR"))

        val reloaded = newRepository()

        assertEquals(1, reloaded.devices.size)
        assertTrue("never taken for a Garmin", reloaded.devices.none { it.id == "w9" })
        assertTrue(dao.rows.containsKey("w9"))
    }

    @Test
    fun `a family the app binds is read with the kind it named, and keeps its own salt`() {
        val known = DeviceIntegration.BuiltIn.mapTo(mutableSetOf()) { it.storageName } + "SOMEVENDOR"
        val repo = BleDeviceRepository(dao, null, java.util.concurrent.Executor(Runnable::run), known)
        val meter = BleDeviceKind("GLUCOSE_METER")
        val first = repo.addDevice("Meter", "AA:BB:CC:DD:EE:01", null, emptySet(), kind = meter, integration = DeviceIntegration("SOMEVENDOR"))
        val second = repo.addDevice("Meter 2", "AA:BB:CC:DD:EE:02", null, emptySet(), kind = meter, integration = DeviceIntegration("SOMEVENDOR"))

        val reloaded = BleDeviceRepository(dao, null, java.util.concurrent.Executor(Runnable::run), known).devices

        assertEquals(listOf(meter, meter), reloaded.map { it.kind })
        assertEquals(DeviceIntegration("SOMEVENDOR"), reloaded.first().integration)
        assertEquals("", first.idSalt)
        assertTrue(second.idSalt.isNotEmpty())
    }

    @Test
    fun concurrentChangesAreAllKept() {
        val repo = newRepository()
        val ids = (0 until 8).map { n ->
            repo.addDevice("D$n", "AA:BB:CC:DD:EE:%02X".format(n), null, emptySet()).id
        }
        val threads = ids.map { id ->
            Thread { repeat(50) { repo.updateBatteryLevel(id, it % 100) } }
        } + Thread { repeat(50) { ids.forEach { repo.markSynced(it, Instant.ofEpochMilli(it.hashCode().toLong())) } } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        assertEquals(8, newRepository().devices.size)
        assertTrue(newRepository().devices.all { it.batteryPercent == 49 && it.lastSyncedAt != null })
    }

    @Test
    fun registryJson_aWatchOfAnUnknownIntegrationIsKeptUnreadNotTakenForAGarmin() {
        val future = """{"id":"w9","displayName":"Ring","address":"AA:BB:CC:DD:EE:09","addedAt":1000,""" +
            """"kind":"WATCH","integration":"SOMEVENDOR"}"""

        val decoded = BleDeviceRegistryJson.decodeKeepingUnreadable("[$future]")!!

        assertTrue(decoded.devices.isEmpty())
        assertEquals(1, decoded.unreadable.size)
    }
}
