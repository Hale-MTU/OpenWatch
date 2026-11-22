package tech.mmarca.openwatch.link

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openlink.OpenLinkConnection
import tech.mmarca.openlink.OpenLinkDeviceKind
import tech.mmarca.openlink.OpenLinkIntegration
import tech.mmarca.openlink.OpenLinkSampleType
import tech.mmarca.openwatch.devices.core.DeviceFamilies
import tech.mmarca.openwatch.devices.core.DeviceFamily
import tech.mmarca.openwatch.devices.core.DeviceFeature
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorCapability
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration

class LinkDevicesTest {

    private val now = Instant.parse("2026-11-08T08:00:00Z")

    /** A family as OpenLink sees one: what it owns and what those devices can do. */
    private class StandIn(
        override val id: String,
        private val features: Set<DeviceFeature>,
        private val owns: (BleSensorDevice) -> Boolean,
    ) : DeviceFamily {
        override fun owns(device: BleSensorDevice) = owns.invoke(device)
        override fun features(device: BleSensorDevice) = features
        override suspend fun forget(device: BleSensorDevice, deleteHistory: Boolean) = Unit
    }

    private val garmin = StandIn(
        "GARMIN",
        setOf(DeviceFeature.SYNC, DeviceFeature.FIND, DeviceFeature.LIVE_HEART_RATE, DeviceFeature.LIVE_STEPS),
    ) { it.kind != BleDeviceKind.SENSOR && (it.integration == DeviceIntegration.GARMIN || it.integration == null) }
    private val wearOs = StandIn("WEAROS", setOf(DeviceFeature.SYNC, DeviceFeature.LIVE_HEART_RATE)) {
        it.kind == BleDeviceKind.WATCH && it.integration == DeviceIntegration.WEAROS
    }
    private val xiaomi = StandIn("XIAOMI", emptySet()) { it.kind == BleDeviceKind.SCALE && it.integration == DeviceIntegration.XIAOMI }
    private val families = DeviceFamilies(mapOf("GARMIN" to garmin, "WEAROS" to wearOs, "XIAOMI" to xiaomi))

    private fun device(
        id: String,
        kind: BleDeviceKind,
        integration: DeviceIntegration? = null,
        capabilities: Set<BleSensorCapability> = emptySet(),
    ) = BleSensorDevice(
        id = id,
        displayName = id,
        address = "AA:BB:CC:DD:EE:0${id.length}",
        bluetoothName = id,
        capabilities = capabilities,
        enabled = true,
        wheelCircumferenceMm = null,
        batteryPercent = 80,
        batteryUpdatedAt = now,
        addedAt = now,
        kind = kind,
        integration = integration,
        lastSyncedAt = now,
    )

    @Test
    fun `watches and the scale are listed, a plain sensor is not`() {
        val devices = linkDevices(
            registered = listOf(
                device("venu", BleDeviceKind.WATCH, DeviceIntegration.GARMIN),
                device("pixel", BleDeviceKind.WATCH, DeviceIntegration.WEAROS),
                device("strap", BleDeviceKind.SENSOR, capabilities = setOf(BleSensorCapability.HEART_RATE)),
                device("bathroom", BleDeviceKind.SCALE, DeviceIntegration.XIAOMI),
            ),
            syncingDeviceIds = setOf("venu"),
            families = families,
            connections = mapOf("pixel" to true, "bathroom" to true),
        )

        // The scale goes by its registry id too: its address stays on the phone.
        assertEquals(listOf("venu", "pixel", "bathroom"), devices.map { it.id })
        val garmin = devices[0]
        assertEquals(OpenLinkIntegration.GARMIN, garmin.integration)
        assertTrue(garmin.syncing)
        assertEquals(80, garmin.batteryPercent)
        assertEquals(now.toEpochMilli(), garmin.lastSyncedAtMillis)
        assertEquals(setOf(OpenLinkSampleType.HEART_RATE, OpenLinkSampleType.STEPS), garmin.liveSampleTypes)
        assertEquals(OpenLinkConnection.CONNECTED, devices[1].connection)
        assertEquals(setOf(OpenLinkSampleType.HEART_RATE), devices[1].liveSampleTypes)
        val scale = devices[2]
        assertEquals(OpenLinkDeviceKind.SCALE, scale.kind)
        assertEquals(OpenLinkIntegration.XIAOMI, scale.integration)
        assertEquals(OpenLinkConnection.CONNECTED, scale.connection)
        assertEquals(false, scale.supportsSync)
        assertEquals(false, scale.supportsRecording)
    }

    @Test
    fun `a legacy watch entry without an integration counts as Garmin`() {
        val devices = linkDevices(listOf(device("fenix", BleDeviceKind.WATCH)), emptySet(), families, emptyMap())

        assertEquals(OpenLinkIntegration.GARMIN, devices.single().integration)
        assertEquals(OpenLinkConnection.UNKNOWN, devices.single().connection)
    }

    @Test
    fun `a family the contract has no name for stays off OpenLink until it does`() {
        val acme = StandIn("ACME", setOf(DeviceFeature.SYNC)) { it.kind == BleDeviceKind.WATCH }

        val devices = linkDevices(
            listOf(device("band", BleDeviceKind.WATCH)),
            emptySet(),
            DeviceFamilies(mapOf("ACME" to acme)),
            emptyMap(),
        )

        assertTrue(devices.isEmpty())
    }
}
