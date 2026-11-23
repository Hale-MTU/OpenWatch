package tech.mmarca.openwatch.data.repository

import android.content.SharedPreferences
import java.util.concurrent.Executor
import tech.mmarca.openwatch.data.local.device.DeviceDao
import tech.mmarca.openwatch.data.local.device.DeviceEntity
import tech.mmarca.openwatch.data.local.device.DeviceStateDao
import tech.mmarca.openwatch.data.local.device.DeviceStateEntity
import tech.mmarca.openwatch.devices.FakeSharedPreferences

/** The `devices` table in memory. */
class FakeDeviceDao : DeviceDao {
    val rows = linkedMapOf<String, DeviceEntity>()

    /** Insertion order, as the table's row ids keep it; an upsert keeps its place. */
    override fun all(): List<DeviceEntity> = rows.values.toList()

    override fun upsert(device: DeviceEntity) {
        rows[device.id] = device
    }

    override fun insertIfAbsent(devices: List<DeviceEntity>) {
        devices.forEach { rows.putIfAbsent(it.id, it) }
    }

    override fun delete(id: String) {
        rows.remove(id)
    }
}

/** A registry over [dao] and [legacy] that loads and writes on the calling thread, for tests. */
fun testDeviceRepository(
    dao: FakeDeviceDao = FakeDeviceDao(),
    legacy: SharedPreferences = FakeSharedPreferences(),
): BleDeviceRepository = BleDeviceRepository(dao, legacy, Executor(Runnable::run))

/** The `device_state` table in memory. */
class FakeDeviceStateDao : DeviceStateDao {
    val rows = linkedMapOf<Pair<String, String>, DeviceStateEntity>()

    override fun all(): List<DeviceStateEntity> = rows.values.toList()

    override fun upsert(state: DeviceStateEntity) {
        rows[state.deviceId to state.key] = state
    }

    override fun delete(deviceId: String, key: String) {
        rows.remove(deviceId to key)
    }
}

/** A per-device state store over [dao] that loads and writes on the calling thread, for tests. */
fun testDeviceStateStore(dao: FakeDeviceStateDao = FakeDeviceStateDao()): DeviceStateStore =
    DeviceStateStore(dao, Executor(Runnable::run), testDeviceRepository())
