package tech.mmarca.openwatch.data.repository.contract

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorCapability
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration

/**
 * The registry in memory, by the app's rules: an address is registered once, a family's first
 * device takes the empty id salt and each later one its own. For the device modules' tests;
 * the app's own tests use the real registry over a fake table.
 */
class FakeDeviceRegistry(initial: List<BleSensorDevice> = emptyList()) : DeviceRegistry {

    private val state = MutableStateFlow(initial)

    override val devicesFlow: Flow<List<BleSensorDevice>> = state

    override val devices: List<BleSensorDevice> get() = state.value

    override fun addDevice(
        displayName: String,
        address: String,
        bluetoothName: String?,
        capabilities: Set<BleSensorCapability>,
        wheelCircumferenceMm: Int?,
        kind: BleDeviceKind?,
        integration: DeviceIntegration?,
    ): BleSensorDevice = synchronized(this) {
        val normalizedAddress = address.uppercase()
        val existing = devices.firstOrNull { it.address.equals(normalizedAddress, ignoreCase = true) }
        if (existing != null) {
            return updateDevice(
                deviceId = existing.id,
                displayName = displayName,
                bluetoothName = bluetoothName,
                capabilities = capabilities.takeIf { it.isNotEmpty() },
                enabled = true,
                wheelCircumferenceMm = wheelCircumferenceMm ?: existing.wheelCircumferenceMm,
                kind = kind,
                integration = integration,
            )
        }
        val resolvedKind = kind ?: BleDeviceKind.SENSOR
        val device = BleSensorDevice(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            address = normalizedAddress,
            bluetoothName = bluetoothName,
            capabilities = capabilities,
            enabled = true,
            wheelCircumferenceMm = wheelCircumferenceMm,
            addedAt = Instant.now(),
            kind = resolvedKind,
            integration = integration,
            idSalt = saltFor(familyOf(resolvedKind, integration), excludingId = null),
        ).normalized()
        state.value = devices + device
        device
    }

    override fun updateDevice(
        deviceId: String,
        displayName: String?,
        bluetoothName: String?,
        capabilities: Set<BleSensorCapability>?,
        enabled: Boolean?,
        wheelCircumferenceMm: Int?,
        kind: BleDeviceKind?,
        integration: DeviceIntegration?,
    ): BleSensorDevice = synchronized(this) {
        val current = devices.firstOrNull { it.id == deviceId } ?: error("Unknown BLE device: $deviceId")
        val nextKind = kind ?: current.kind
        val nextIntegration = integration ?: current.integration
        val family = familyOf(nextKind, nextIntegration)
        val salt = if (family != null && family != familyOf(current.kind, current.integration)) {
            saltFor(family, excludingId = deviceId)
        } else {
            current.idSalt
        }
        val updated = current.copy(
            displayName = displayName ?: current.displayName,
            bluetoothName = bluetoothName ?: current.bluetoothName,
            capabilities = capabilities ?: current.capabilities,
            enabled = enabled ?: current.enabled,
            wheelCircumferenceMm = wheelCircumferenceMm ?: current.wheelCircumferenceMm,
            kind = nextKind,
            integration = nextIntegration,
            idSalt = salt,
        ).normalized()
        state.value = devices.map { if (it.id == deviceId) updated else it }
        updated
    }

    override fun removeDevice(deviceId: String) {
        synchronized(this) { state.value = devices.filterNot { it.id == deviceId } }
    }

    override fun updateBatteryLevel(deviceId: String, batteryPercent: Int) {
        synchronized(this) {
            val percent = batteryPercent.coerceIn(0, 100)
            val current = devices.firstOrNull { it.id == deviceId } ?: return
            if (current.batteryPercent == percent) return
            val updated = current.copy(batteryPercent = percent, batteryUpdatedAt = Instant.now())
            state.value = devices.map { if (it.id == deviceId) updated else it }
        }
    }

    override fun markSynced(deviceId: String, at: Instant) {
        synchronized(this) {
            state.value = devices.map { if (it.id == deviceId) it.copy(lastSyncedAt = at) else it }
        }
    }

    private fun saltFor(family: String?, excludingId: String?): String {
        if (family == null) return ""
        val taken = devices.any { it.id != excludingId && familyOf(it.kind, it.integration) == family }
        return if (taken) UUID.randomUUID().toString().take(SALT_LENGTH) else ""
    }

    private fun familyOf(kind: BleDeviceKind, integration: DeviceIntegration?): String? = when {
        integration != null -> integration.storageName
        kind == BleDeviceKind.WATCH || kind == BleDeviceKind.BIKE_COMPUTER -> DeviceIntegration.GARMIN.storageName
        else -> null
    }

    private companion object {
        const val SALT_LENGTH = 8
    }
}
