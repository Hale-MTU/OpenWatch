package tech.mmarca.openwatch.devices.core

import tech.mmarca.openwatch.domain.model.BleSensorDevice

/** A family as the app's consumers see one: what it owns and what those devices can do. Forgets nothing. */
class FakeDeviceFamily(
    override val id: String,
    private val features: Set<DeviceFeature>,
    private val owns: (BleSensorDevice) -> Boolean,
) : DeviceFamily {
    override fun owns(device: BleSensorDevice): Boolean = owns.invoke(device)

    override fun features(device: BleSensorDevice): Set<DeviceFeature> = features

    override suspend fun forget(device: BleSensorDevice, deleteHistory: Boolean): Unit = Unit
}
