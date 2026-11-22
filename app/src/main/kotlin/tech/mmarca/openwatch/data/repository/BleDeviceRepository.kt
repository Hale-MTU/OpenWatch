package tech.mmarca.openwatch.data.repository

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorCapability
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration

/**
 * The paired devices, one JSON list in preferences.
 *
 * Every change reads and rewrites the whole list, from the UI, a sync and a Bluetooth
 * callback at once, so changes run one at a time under [lock]. An entry this version
 * cannot read is kept as it was and written back, and a list that does not parse at all
 * is copied aside before anything replaces it: one bad entry must not forget every device.
 */
@Singleton
class BleDeviceRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val lock = Any()

    /** Entries this version could not read, as stored. Written back with every change. */
    @Volatile
    private var unreadable: List<String> = emptyList()
    private val _devices = MutableStateFlow(readDevices())
    val devicesFlow: StateFlow<List<BleSensorDevice>> = _devices.asStateFlow()

    val devices: List<BleSensorDevice>
        get() = _devices.value

    val enabledDevices: List<BleSensorDevice>
        get() = devices.filter { it.enabled }

    fun refresh() = synchronized(lock) {
        _devices.value = readDevices()
    }

    fun resolveCapabilityAssignments(): Map<BleSensorCapability, BleSensorDevice> {
        val assignments = linkedMapOf<BleSensorCapability, BleSensorDevice>()
        enabledDevices.forEach { device ->
            // Only devices with capabilities take part. A sync-only watch has none.
            if (!device.isLiveSensorCapable) return@forEach
            device.capabilities.forEach { capability ->
                assignments.putIfAbsent(capability, device)
            }
        }
        return assignments
    }

    fun capabilityConflicts(
        capabilities: Set<BleSensorCapability>,
        excludingDeviceId: String? = null,
    ): Map<BleSensorCapability, BleSensorDevice> =
        resolveCapabilityAssignments()
            .filter { (capability, device) ->
                capability in capabilities && device.id != excludingDeviceId
            }

    /**
     * Registers [address], or folds these fields into the existing entry.
     * The Sensors screen owns [capabilities], watch onboarding owns [kind]
     * and [integration]; each update leaves the other half alone.
     */
    fun addDevice(
        displayName: String,
        address: String,
        bluetoothName: String?,
        capabilities: Set<BleSensorCapability>,
        wheelCircumferenceMm: Int? = null,
        kind: BleDeviceKind? = null,
        integration: DeviceIntegration? = null,
    ): BleSensorDevice = synchronized(lock) {
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
        val device = BleSensorDevice(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            address = normalizedAddress,
            bluetoothName = bluetoothName,
            capabilities = capabilities,
            enabled = true,
            wheelCircumferenceMm = wheelCircumferenceMm,
            addedAt = Instant.now(),
            kind = kind ?: BleDeviceKind.SENSOR,
            integration = integration,
        ).normalized()
        persist(devices + device)
        device
    }

    fun updateDevice(
        deviceId: String,
        displayName: String? = null,
        /** The advertised name, which a re-add refreshes; null keeps the stored one. */
        bluetoothName: String? = null,
        capabilities: Set<BleSensorCapability>? = null,
        enabled: Boolean? = null,
        wheelCircumferenceMm: Int? = null,
        kind: BleDeviceKind? = null,
        integration: DeviceIntegration? = null,
    ): BleSensorDevice = synchronized(lock) {
        val current = devices.firstOrNull { it.id == deviceId }
            ?: error("Unknown BLE device: $deviceId")
        val updated = current.copy(
            displayName = displayName ?: current.displayName,
            bluetoothName = bluetoothName ?: current.bluetoothName,
            capabilities = capabilities ?: current.capabilities,
            enabled = enabled ?: current.enabled,
            wheelCircumferenceMm = wheelCircumferenceMm ?: current.wheelCircumferenceMm,
            kind = kind ?: current.kind,
            integration = integration ?: current.integration,
        ).normalized()
        persist(devices.map { if (it.id == deviceId) updated else it })
        updated
    }

    fun removeDevice(deviceId: String) = synchronized(lock) {
        // Garmin-specific state is cleared by whoever forgets the watch.
        persist(devices.filterNot { it.id == deviceId })
    }

    fun setDeviceEnabled(deviceId: String, enabled: Boolean) {
        updateDevice(deviceId = deviceId, enabled = enabled)
    }

    fun updateBatteryLevel(deviceId: String, batteryPercent: Int) = synchronized(lock) {
        val percent = batteryPercent.coerceIn(0, 100)
        // Only a changed reading is written; the stamp says when the level moved.
        val current = devices.firstOrNull { it.id == deviceId } ?: return@synchronized
        if (current.batteryPercent == percent) return@synchronized
        persist(
            devices.map { device ->
                if (device.id == deviceId) {
                    device.copy(
                        batteryPercent = percent,
                        batteryUpdatedAt = Instant.now(),
                    ).normalized()
                } else {
                    device
                }
            },
        )
    }

    /** Stamps a watch's last completed file pull. A no-op for an unknown id. */
    fun markSynced(deviceId: String, at: Instant) = synchronized(lock) {
        if (devices.none { it.id == deviceId }) return@synchronized
        persist(
            devices.map { device ->
                if (device.id == deviceId) device.copy(lastSyncedAt = at) else device
            },
        )
    }

    /** Called under [lock]. */
    private fun persist(nextDevices: List<BleSensorDevice>) {
        prefs.edit {
            putString(KEY_DEVICES, BleDeviceRegistryJson.encode(nextDevices, unreadable))
        }
        _devices.value = nextDevices
    }

    private fun readDevices(): List<BleSensorDevice> {
        val stored = prefs.getString(KEY_DEVICES, null) ?: return emptyList()
        val registry = BleDeviceRegistryJson.decodeKeepingUnreadable(stored)
        if (registry == null) {
            // Not a list at all. Keep the first copy aside, so the next change cannot erase it.
            if (!prefs.contains(KEY_UNPARSEABLE_COPY)) prefs.edit { putString(KEY_UNPARSEABLE_COPY, stored) }
            unreadable = emptyList()
            return emptyList()
        }
        unreadable = registry.unreadable
        return registry.devices
    }

    private companion object {
        const val PREFS_FILE = "ble_sensor_devices"
        const val KEY_DEVICES = "devices"
        const val KEY_UNPARSEABLE_COPY = "devices_unparseable"
    }
}

/**
 * The registry's JSON format, shared with the Flutter build. Reading is
 * tolerant: absent or unknown fields fall back to a plain sensor.
 */
internal object BleDeviceRegistryJson {

    /** The registry read back: what this version understands, and the rest as stored. */
    data class Decoded(val devices: List<BleSensorDevice>, val unreadable: List<String>)

    /** [devices] as JSON, followed by the [unreadable] entries exactly as they were read. */
    fun encode(devices: List<BleSensorDevice>, unreadable: List<String> = emptyList()): String =
        JSONArray(
            devices.map { device ->
                JSONObject()
                    .put("id", device.id)
                    .put("displayName", device.displayName)
                    .put("address", device.address)
                    .put("bluetoothName", device.bluetoothName ?: JSONObject.NULL)
                    .put(
                        "capabilities",
                        JSONArray(device.capabilities.map { it.name }),
                    )
                    .put("enabled", device.enabled)
                    .put(
                        "wheelCircumferenceMm",
                        device.wheelCircumferenceMm ?: JSONObject.NULL,
                    )
                    .put("batteryPercent", device.batteryPercent ?: JSONObject.NULL)
                    .put(
                        "batteryUpdatedAt",
                        device.batteryUpdatedAt?.toEpochMilli() ?: JSONObject.NULL,
                    )
                    .put("addedAt", device.addedAt.toEpochMilli())
                    .put("kind", device.kind.storageName)
                    .put("integration", device.integration?.storageName ?: JSONObject.NULL)
                    .put("lastSyncedAt", device.lastSyncedAt?.toEpochMilli() ?: JSONObject.NULL)
            } + unreadable.map { JSONObject(it) },
        ).toString()

    /** The devices in [json]; an entry this version cannot read is left out. */
    fun decode(json: String): List<BleSensorDevice> = decodeKeepingUnreadable(json)?.devices.orEmpty()

    /**
     * Each entry on its own: one this version cannot read goes to [Decoded.unreadable] as it
     * was stored, and the rest are still read. Null when [json] is not a list at all.
     */
    fun decodeKeepingUnreadable(json: String): Decoded? {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return null
        val devices = mutableListOf<BleSensorDevice>()
        val unreadable = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
            val device = item?.let { runCatching { decodeEntry(it) }.getOrNull() }
            when {
                device != null -> devices += device
                item != null -> unreadable += item.toString()
                // Not an object at all: nothing that could be a device to keep.
            }
        }
        return Decoded(devices, unreadable)
    }

    private fun decodeEntry(item: JSONObject): BleSensorDevice {
        val capabilities = buildSet {
            val caps = item.optJSONArray("capabilities")
            if (caps != null) {
                for (capIndex in 0 until caps.length()) {
                    val name = caps.optString(capIndex)
                    runCatching { add(BleSensorCapability.valueOf(name)) }
                }
            }
        }
        return BleSensorDevice(
            id = item.getString("id"),
            displayName = item.getString("displayName"),
            address = item.getString("address"),
            bluetoothName = item.optString("bluetoothName").takeIf { it.isNotBlank() },
            capabilities = capabilities,
            enabled = item.optBoolean("enabled", true),
            wheelCircumferenceMm = item.opt("wheelCircumferenceMm")
                .takeIf { it != JSONObject.NULL }
                ?.let { (it as Number).toInt() }
                ?.takeIf { it > 0 },
            batteryPercent = item.opt("batteryPercent")
                .takeIf { it != null && it != JSONObject.NULL }
                ?.let { (it as Number).toInt() },
            batteryUpdatedAt = item.opt("batteryUpdatedAt")
                .takeIf { it != null && it != JSONObject.NULL }
                ?.let { Instant.ofEpochMilli((it as Number).toLong()) },
            addedAt = Instant.ofEpochMilli(item.getLong("addedAt")),
            // Absent before watches existed; unknown values degrade the same way.
            kind = item.optString("kind")
                .let { BleDeviceKind.fromStorage(it) }
                ?: BleDeviceKind.SENSOR,
            // Absent (null) reads back as Garmin (`devices/garmin/isGarmin`). A name this version does not
            // know is a device from a newer build: the entry is kept unread, never taken for a Garmin.
            integration = item.optString("integration").takeIf { it.isNotBlank() }?.let { name ->
                requireNotNull(DeviceIntegration.fromStorage(name)) { "unknown integration" }
            },
            lastSyncedAt = item.opt("lastSyncedAt")
                .takeIf { it != null && it != JSONObject.NULL }
                ?.let { Instant.ofEpochMilli((it as Number).toLong()) },
        ).normalized()
    }
}
