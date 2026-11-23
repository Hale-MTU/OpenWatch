package tech.mmarca.openwatch.devices.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.devices.garmin.isGarmin
import tech.mmarca.openwatch.devices.garmin.isGarminWatch
import tech.mmarca.openwatch.devices.wearos.isWearOs
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration

class DeviceKindTest {

    @Test
    fun `storage names match what the Flutter build persisted`() {
        assertEquals("SENSOR", BleDeviceKind.SENSOR.storageName)
        assertEquals("WATCH", BleDeviceKind.WATCH.storageName)
        assertEquals("BIKE_COMPUTER", BleDeviceKind.BIKE_COMPUTER.storageName)
        assertEquals("GARMIN", DeviceIntegration.GARMIN.storageName)
        assertEquals("WEAROS", DeviceIntegration.WEAROS.storageName)
    }

    @Test
    fun `fromStorage resolves the built-in names, keeps a name a family gave, and rejects a blank one`() {
        assertEquals(BleDeviceKind.WATCH, BleDeviceKind.fromStorage("WATCH"))
        // Open sets: a family names its own kinds and itself in its own module.
        assertEquals("GLUCOSE_METER", BleDeviceKind.fromStorage("GLUCOSE_METER")?.storageName)
        assertEquals(BleDeviceKind("GLUCOSE_METER"), BleDeviceKind.fromStorage("GLUCOSE_METER"))
        assertNull(BleDeviceKind.fromStorage(" "))
        assertEquals(DeviceIntegration.WEAROS, DeviceIntegration.fromStorage("WEAROS"))
        assertEquals(DeviceIntegration("SOMEVENDOR"), DeviceIntegration.fromStorage("SOMEVENDOR"))
        assertNull(DeviceIntegration.fromStorage(""))
    }

    @Test
    fun `a family's own kind is no watch, no bike computer and no Garmin`() {
        val meter = device(kind = BleDeviceKind("GLUCOSE_METER"), integration = DeviceIntegration("SOMEVENDOR"))
        assertFalse(meter.isWatch)
        assertFalse(meter.isBikeComputer)
        assertFalse(meter.isGarmin)
        assertFalse(meter.isWearOs)
        assertFalse(meter.isLiveSensorCapable)
        // A watch of a family this version does not know is never taken for a Garmin.
        assertFalse(device(kind = BleDeviceKind.WATCH, integration = DeviceIntegration("SOMEVENDOR")).isGarmin)
    }

    @Test
    fun `a default device is a plain live sensor`() {
        val device = device()
        assertEquals(BleDeviceKind.SENSOR, device.kind)
        assertNull(device.integration)
        assertNull(device.lastSyncedAt)
        assertFalse(device.isWatch)
        assertFalse(device.isGarmin)
        assertTrue(device.isLiveSensorCapable)
        // A sensor is neither kind of watch nor a bike computer, whatever its integration.
        assertFalse(device.isGarminWatch)
        assertFalse(device.isWearOs)
        assertFalse(device.isBikeComputer)
        val stamped = device(integration = DeviceIntegration.GARMIN)
        assertFalse(stamped.isGarminWatch)
        assertFalse(stamped.isWearOs)
        assertFalse(stamped.isBikeComputer)
    }

    @Test
    fun `an explicit Garmin watch is a Garmin watch`() {
        val watch = device(kind = BleDeviceKind.WATCH, integration = DeviceIntegration.GARMIN)
        assertTrue(watch.isGarminWatch)
        assertFalse(watch.isWearOs)
        // A watch pulls FIT files over GFDI and streams nothing live.
        assertTrue(watch.isGarmin)
        assertFalse(watch.isLiveSensorCapable)
    }

    @Test
    fun `a null-integration watch is legacy Garmin`() {
        // A Garmin watch stored before the integration field existed must keep reading as Garmin.
        val watch = device(kind = BleDeviceKind.WATCH)
        assertTrue(watch.isWatch)
        assertTrue(watch.isGarminWatch)
        assertTrue(watch.isGarmin)
        assertFalse(watch.isWearOs)
        assertFalse(watch.isLiveSensorCapable)
    }

    @Test
    fun `a wearos watch is a watch but never on the Garmin sync path`() {
        val watch = device(kind = BleDeviceKind.WATCH, integration = DeviceIntegration.WEAROS)
        assertTrue(watch.isWatch)
        assertTrue(watch.isWearOs)
        assertFalse(watch.isGarminWatch)
        assertFalse(watch.isGarmin)
        assertFalse(watch.isLiveSensorCapable)
    }

    @Test
    fun `a bike computer syncs over GFDI and can still be a live sensor`() {
        val edge = device(kind = BleDeviceKind.BIKE_COMPUTER, integration = DeviceIntegration.GARMIN)
        assertFalse(edge.isWatch)
        assertTrue(edge.isBikeComputer)
        assertTrue(edge.isGarmin)
        assertFalse(edge.isGarminWatch)
        assertFalse(edge.isWearOs)
        assertTrue(edge.isLiveSensorCapable)
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
