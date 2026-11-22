package tech.mmarca.openwatch.data.repository

import android.content.SharedPreferences
import java.util.concurrent.Executor
import tech.mmarca.openwatch.data.local.device.DeviceDao
import tech.mmarca.openwatch.data.local.device.DeviceEntity
import tech.mmarca.openwatch.devices.FakeSharedPreferences

/** The `devices` table in memory, in the table's order. */
class FakeDeviceDao : DeviceDao {
    val rows = linkedMapOf<String, DeviceEntity>()

    override fun all(): List<DeviceEntity> = rows.values.sortedWith(compareBy({ it.addedAt }, { it.id }))

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
