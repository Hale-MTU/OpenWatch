package tech.mmarca.openwatch.data.repository.contract

/** The `device_state` table in memory, by the app store's rules. [values] is what the table would hold. */
class FakeDeviceStateRepository : DeviceStateRepository {

    /** Each device's values, by key. */
    val values = linkedMapOf<String, LinkedHashMap<String, String>>()

    override fun get(deviceId: String, key: String): String? = values[deviceId]?.get(key)

    override fun put(deviceId: String, key: String, value: String?) {
        if (value == null) {
            values[deviceId]?.remove(key)
            if (values[deviceId]?.isEmpty() == true) values.remove(deviceId)
        } else {
            values.getOrPut(deviceId) { linkedMapOf() }[key] = value
        }
    }

    override fun adopt(values: Map<String, Map<String, String>>, registered: Set<String>) {
        for ((deviceId, pairs) in values) {
            if (deviceId !in registered) continue
            for ((key, value) in pairs) if (get(deviceId, key) == null) put(deviceId, key, value)
        }
    }

    override fun forget(deviceId: String) {
        values.remove(deviceId)
    }
}
