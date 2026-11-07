package tech.mmarca.openwatch.domain.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleDeviceKindTest {

    @Test
    fun `storage names match what the Flutter build persisted`() {
        assertEquals("SENSOR", BleDeviceKind.SENSOR.storageName)
        assertEquals("WATCH", BleDeviceKind.WATCH.storageName)
        assertEquals("WEAROS", DeviceIntegration.WEAROS.storageName)
    }

    @Test
    fun `fromStorage resolves known names and rejects unknown ones`() {
        assertEquals(BleDeviceKind.WATCH, BleDeviceKind.fromStorage("WATCH"))
        assertNull(BleDeviceKind.fromStorage("HOLOGRAPH"))
        // Dropped integrations and kinds read back as unknown, not as something else.
        assertNull(BleDeviceKind.fromStorage("BIKE_COMPUTER"))
        assertEquals(DeviceIntegration.WEAROS, DeviceIntegration.fromStorage("WEAROS"))
        assertNull(DeviceIntegration.fromStorage("GARMIN"))
        assertNull(DeviceIntegration.fromStorage(""))
    }

    @Test
    fun `a default device is a plain sensor, not a watch`() {
        val device = device()
        assertEquals(BleDeviceKind.SENSOR, device.kind)
        assertNull(device.integration)
        assertNull(device.lastSyncedAt)
        assertFalse(device.isWatch)
        assertFalse(device.isWearosWatch)
        // A sensor is no watch, whatever its integration.
        assertFalse(device(integration = DeviceIntegration.WEAROS).isWearosWatch)
    }

    @Test
    fun `a wearos watch is a watch`() {
        val watch = device(kind = BleDeviceKind.WATCH, integration = DeviceIntegration.WEAROS)
        assertTrue(watch.isWatch)
        assertTrue(watch.isWearosWatch)
    }

    @Test
    fun `a watch without an integration is not a wearos watch`() {
        val watch = device(kind = BleDeviceKind.WATCH)
        assertTrue(watch.isWatch)
        assertFalse(watch.isWearosWatch)
    }

    private fun device(
        kind: BleDeviceKind = BleDeviceKind.SENSOR,
        integration: DeviceIntegration? = null,
    ): BleSensorDevice = BleSensorDevice(
        id = "id",
        displayName = "Device",
        address = "AA:BB:CC:DD:EE:01",
        bluetoothName = null,
        capabilities = emptySet(),
        enabled = true,
        wheelCircumferenceMm = null,
        addedAt = Instant.EPOCH,
        kind = kind,
        integration = integration,
    )
}
