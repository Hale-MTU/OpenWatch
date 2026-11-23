package tech.mmarca.openwatch.data.repository

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import tech.mmarca.openwatch.data.local.device.DeviceStateDao
import tech.mmarca.openwatch.data.local.device.DeviceStateEntity
import tech.mmarca.openwatch.data.local.device.DeviceStorageExecutor
import tech.mmarca.openwatch.data.repository.contract.DeviceStateRepository

/**
 * What integrations keep per device, by device id and key: the `device_state` table, read once
 * into memory and written behind it, as the registry is. Removing a device's row takes its
 * values with it. Values are text; each integration's store types its own keys.
 *
 * [registry] is a constructor parameter so the registry is built first: its load, which imports
 * the device rows, runs on the shared thread before this load and before any import here.
 */
@Singleton
class DeviceStateStore @Inject constructor(
    private val dao: DeviceStateDao,
    @param:DeviceStorageExecutor private val writer: Executor,
    @Suppress("unused") registry: BleDeviceRepository,
) : DeviceStateRepository {
    private val loaded = CountDownLatch(1)
    private val values = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    init {
        writer.execute {
            try {
                dao.all().forEach { row -> values.getOrPut(row.deviceId) { ConcurrentHashMap() }[row.key] = row.value }
            } catch (error: Exception) {
                runCatching { Log.w(TAG, "device state load failed: ${error::class.simpleName}") }
            } finally {
                loaded.countDown()
            }
        }
    }

    override fun get(deviceId: String, key: String): String? {
        loaded.await()
        return values[deviceId]?.get(key)
    }

    override fun keys(deviceId: String): Set<String> {
        loaded.await()
        return values[deviceId]?.keys?.toSet().orEmpty()
    }

    /** Keeps [value] for [deviceId]'s [key]; null forgets it. */
    override fun put(deviceId: String, key: String, value: String?) {
        loaded.await()
        if (value == null) {
            values[deviceId]?.remove(key) ?: return
            persist { dao.delete(deviceId, key) }
        } else {
            values.getOrPut(deviceId) { ConcurrentHashMap() }[key] = value
            persist { dao.upsert(DeviceStateEntity(deviceId, key, value)) }
        }
    }

    /**
     * Takes over values an integration kept elsewhere, for the devices [registered], once:
     * only where nothing is kept yet. A value for a device no longer registered is dropped.
     */
    override fun adopt(values: Map<String, Map<String, String>>, registered: Set<String>) {
        for ((deviceId, pairs) in values) {
            if (deviceId !in registered) continue
            for ((key, value) in pairs) if (get(deviceId, key) == null) put(deviceId, key, value)
        }
    }

    /** Forgets everything kept for [deviceId], as removing its registry row also does in the table. */
    override fun forget(deviceId: String) {
        val keys = values.remove(deviceId)?.keys.orEmpty()
        persist { keys.forEach { dao.delete(deviceId, it) } }
    }

    private fun persist(write: () -> Unit) {
        writer.execute {
            try {
                write()
            } catch (error: Exception) {
                runCatching { Log.w(TAG, "device state write failed: ${error::class.simpleName}") }
            }
        }
    }

    private companion object {
        const val TAG = "DeviceStateStore"
    }
}
