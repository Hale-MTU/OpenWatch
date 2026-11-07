package tech.mmarca.openwatch.devices.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openwatch.devices.wearos.WearOsDeviceClassifier
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleDiscoveredDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration

class DeviceClassificationTest {

    private val classifiers = listOf(WearOsDeviceClassifier())

    @Test
    fun `a wearos-style smartwatch classifies as a wearos watch`() {
        val verdict = classifyDevice(discovered("Galaxy Watch8 (89FZ)"), classifiers)
        assertEquals(DeviceIntegration.WEAROS, verdict.integration)
        assertEquals(BleDeviceKind.WATCH, verdict.kind)
    }

    @Test
    fun `an unnamed, unremarkable device → sensor`() {
        assertNull(classifyDevice(discovered(), classifiers).integration)
    }

    @Test
    fun `anything unclaimed falls through to a plain sensor`() {
        val verdict = classifyDevice(discovered("Polar H10"), classifiers)
        assertEquals(DeviceClassification.SENSOR, verdict)
        assertNull(verdict.integration)
        assertEquals(BleDeviceKind.SENSOR, verdict.kind)
    }

    @Test
    fun `a Garmin watch is not offered as a watch`() {
        // No integration claims it any more, and the Wear OS names do not match it.
        assertEquals(DeviceClassification.SENSOR, classifyDevice(discovered("Garmin Forerunner 265S"), classifiers))
        assertEquals(DeviceClassification.SENSOR, classifyDevice(discovered("vívoactive 5"), classifiers))
    }

    private fun discovered(name: String? = null): BleDiscoveredDevice = BleDiscoveredDevice(
        address = "AA:BB:CC:DD:EE:01",
        name = name,
        rssi = -60,
        suggestedCapabilities = emptySet(),
    )
}
