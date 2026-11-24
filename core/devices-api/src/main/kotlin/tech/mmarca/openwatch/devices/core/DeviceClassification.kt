package tech.mmarca.openwatch.devices.core

import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleDiscoveredDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration

/** How the app treats a discovered device: the owning integration (null for a plain sensor) and its kind. */
data class DeviceClassification(
    val integration: DeviceIntegration? = null,
    val kind: BleDeviceKind,
) {
    companion object {
        /** The default: a live BLE sensor belonging to no file-sync integration. */
        val SENSOR = DeviceClassification(kind = BleDeviceKind.SENSOR)
    }
}

/** One integration's verdict on a scanned device, or null when it is not its own. */
fun interface DeviceClassifier {
    fun classify(device: BleDiscoveredDevice): DeviceClassification?
}

/** The first [classifiers] verdict that claims [device], else a plain sensor. Stronger signals first. */
fun classifyDevice(
    device: BleDiscoveredDevice,
    classifiers: Iterable<DeviceClassifier>,
): DeviceClassification {
    for (classifier in classifiers) {
        val verdict = classifier.classify(device)
        if (verdict != null) return verdict
    }
    return DeviceClassification.SENSOR
}
