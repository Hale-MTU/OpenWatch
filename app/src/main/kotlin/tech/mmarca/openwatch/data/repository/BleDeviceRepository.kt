package tech.mmarca.openwatch.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import org.json.JSONArray
import org.json.JSONObject
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorCapability
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration
import tech.mmarca.openwatch.data.local.device.DeviceDao
import tech.mmarca.openwatch.data.local.device.DeviceEntity
import tech.mmarca.openwatch.data.local.device.DeviceStorageExecutor

/**
 * The paired devices: the `devices` table, read once into memory and written behind it.
 *
 * Every read is the in-memory list, so callers on any thread read it at once, as they did while
 * this was a preferences file. The first read waits for the load, a handful of rows at process
 * start, as a first read of SharedPreferences always waited for its file. Changes run one at a
 * time under [lock], take effect in the list at once, and reach the table in order on the
 * registry's own thread.
 *
 * The JSON list in `ble_sensor_devices` that held the registry until 2026-11 is imported once,
 * at the first load, and left in place: an entry this version could not read stays there.
 */
@Singleton
class BleDeviceRepository(
    private val dao: DeviceDao,
    private val legacy: SharedPreferences?,
    private val writer: Executor,
) {
    @Inject
    constructor(
        dao: DeviceDao,
        @ApplicationContext context: Context,
        @DeviceStorageExecutor writer: Executor,
    ) : this(
        dao = dao,
        legacy = context.getSharedPreferences(LEGACY_PREFS_FILE, Context.MODE_PRIVATE),
        writer = writer,
    )

    private val lock = Any()
    private val loaded = CountDownLatch(1)
    private val _devices = MutableStateFlow<List<BleSensorDevice>?>(null)

    /** The devices, from the first load on. */
    val devicesFlow: Flow<List<BleSensorDevice>> = _devices.filterNotNull()

    val devices: List<BleSensorDevice>
        get() {
            loaded.await()
            return _devices.value.orEmpty()
        }

    val enabledDevices: List<BleSensorDevice>
        get() = devices.filter { it.enabled }

    init {
        writer.execute(::load)
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
        write(devices + device, changed = device)
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
        val nextKind = kind ?: current.kind
        val nextIntegration = integration ?: current.integration
        // A sensor that becomes a watch joins a family now, and takes its salt then.
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
        write(devices.map { if (it.id == deviceId) updated else it }, changed = updated)
        updated
    }

    fun removeDevice(deviceId: String) = synchronized(lock) {
        if (devices.none { it.id == deviceId }) return@synchronized
        // What an integration kept in device_state goes with the row; the rest is its family's to forget.
        write(devices.filterNot { it.id == deviceId }, removedId = deviceId)
    }

    fun setDeviceEnabled(deviceId: String, enabled: Boolean) {
        updateDevice(deviceId = deviceId, enabled = enabled)
    }

    fun updateBatteryLevel(deviceId: String, batteryPercent: Int) = synchronized(lock) {
        val percent = batteryPercent.coerceIn(0, 100)
        // Only a changed reading is written; the stamp says when the level moved.
        val current = devices.firstOrNull { it.id == deviceId } ?: return@synchronized
        if (current.batteryPercent == percent) return@synchronized
        val updated = current.copy(batteryPercent = percent, batteryUpdatedAt = Instant.now()).normalized()
        write(devices.map { if (it.id == deviceId) updated else it }, changed = updated)
    }

    /** Stamps a watch's last completed file pull. A no-op for an unknown id. */
    fun markSynced(deviceId: String, at: Instant) = synchronized(lock) {
        val current = devices.firstOrNull { it.id == deviceId } ?: return@synchronized
        val updated = current.copy(lastSyncedAt = at)
        write(devices.map { if (it.id == deviceId) updated else it }, changed = updated)
    }

    /** Called under [lock]: the list at once, the table behind it, in order. */
    private fun write(next: List<BleSensorDevice>, changed: BleSensorDevice? = null, removedId: String? = null) {
        _devices.value = next
        val row = changed?.toEntity()
        writer.execute {
            try {
                row?.let(dao::upsert)
                removedId?.let(dao::delete)
            } catch (error: Exception) {
                // A full disk, or a database closed under it: the list in memory holds until the next start.
                runCatching { Log.w(TAG, "registry write failed: ${error::class.simpleName}") }
            }
        }
    }

    private fun load() {
        try {
            importLegacyOnce()
            // A row this version cannot read, from a newer build, stays in the table unread.
            _devices.value = dao.all().mapNotNull { it.toModelOrNull() }
        } catch (error: Exception) {
            runCatching { Log.w(TAG, "registry load failed: ${error::class.simpleName}") }
            _devices.value = emptyList()
        } finally {
            loaded.countDown()
        }
    }

    /** The JSON registry of before, into the table once. A row already there wins. */
    private fun importLegacyOnce() {
        val prefs = legacy ?: return
        if (prefs.getBoolean(KEY_IMPORTED, false)) return
        val registry = prefs.getString(KEY_DEVICES, null)?.let(BleDeviceRegistryJson::decodeKeepingUnreadable)
        if (registry != null) dao.insertIfAbsent(registry.devices.map { it.toEntity() })
        prefs.edit(commit = true) { putBoolean(KEY_IMPORTED, true) }
    }

    /**
     * The salt a device of [family] takes: empty for the family's first, so its records keep the
     * ids they had before salts existed, and a fresh one for each after it.
     */
    private fun saltFor(family: String?, excludingId: String?): String {
        if (family == null) return ""
        val taken = devices.any { it.id != excludingId && familyOf(it.kind, it.integration) == family }
        return if (taken) UUID.randomUUID().toString().take(SALT_LENGTH) else ""
    }

    private companion object {
        const val TAG = "BleDeviceRepository"
        const val LEGACY_PREFS_FILE = "ble_sensor_devices"
        const val KEY_DEVICES = "devices"
        const val KEY_IMPORTED = "devices_imported_to_room"
        const val SALT_LENGTH = 8
    }
}

/**
 * The family a device's records belong to, for its salt: its integration, and Garmin for a watch
 * or bike computer stored before the integration was (it always was a Garmin). Null for a
 * plain sensor, which writes no records of its own.
 */
internal fun familyOf(kind: BleDeviceKind, integration: DeviceIntegration?): String? = when {
    integration != null -> integration.storageName
    kind == BleDeviceKind.WATCH || kind == BleDeviceKind.BIKE_COMPUTER -> DeviceIntegration.GARMIN.storageName
    else -> null
}

internal fun BleSensorDevice.toEntity() = DeviceEntity(
    id = id,
    integration = integration?.storageName,
    kind = kind.storageName,
    displayName = displayName,
    address = address,
    bluetoothName = bluetoothName,
    capabilities = capabilities.joinToString(",") { it.name },
    enabled = enabled,
    wheelCircumferenceMm = wheelCircumferenceMm,
    batteryPercent = batteryPercent,
    batteryUpdatedAt = batteryUpdatedAt?.toEpochMilli(),
    addedAt = addedAt.toEpochMilli(),
    lastSyncedAt = lastSyncedAt?.toEpochMilli(),
    idSalt = idSalt,
)

/** The model of [this] row, or null for an integration or kind this version does not know. */
internal fun DeviceEntity.toModelOrNull(): BleSensorDevice? {
    val knownIntegration = integration?.let { DeviceIntegration.fromStorage(it) ?: return null }
    return BleSensorDevice(
        id = id,
        displayName = displayName,
        address = address,
        bluetoothName = bluetoothName,
        capabilities = capabilities.split(',').filter { it.isNotBlank() }
            .mapNotNullTo(mutableSetOf()) { name -> runCatching { BleSensorCapability.valueOf(name) }.getOrNull() },
        enabled = enabled,
        wheelCircumferenceMm = wheelCircumferenceMm,
        batteryPercent = batteryPercent,
        batteryUpdatedAt = batteryUpdatedAt?.let(Instant::ofEpochMilli),
        addedAt = Instant.ofEpochMilli(addedAt),
        kind = BleDeviceKind.fromStorage(kind) ?: return null,
        integration = knownIntegration,
        lastSyncedAt = lastSyncedAt?.let(Instant::ofEpochMilli),
        idSalt = idSalt,
    ).normalized()
}

/**
 * The registry's JSON format of before, shared with the Flutter build: what the registry imports
 * once. Reading is tolerant: absent or unknown fields fall back to a plain sensor.
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
