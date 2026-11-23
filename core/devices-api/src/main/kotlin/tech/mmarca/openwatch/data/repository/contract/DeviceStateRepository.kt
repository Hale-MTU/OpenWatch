package tech.mmarca.openwatch.data.repository.contract

/**
 * What an integration keeps per device, by device id and key, as text: its cursors, switches and
 * learnt capabilities. Removing a device's registry row takes its values with it. Each
 * integration's store types its own keys. The app's `DeviceStateStore` implements it on the
 * `device_state` table; a device module takes this, never the class.
 */
interface DeviceStateRepository {

    fun get(deviceId: String, key: String): String?

    /** Keeps [value] for [deviceId]'s [key]; null forgets it. */
    fun put(deviceId: String, key: String, value: String?)

    /**
     * Takes over values an integration kept elsewhere, for the devices [registered], once:
     * only where nothing is kept yet. A value for a device no longer registered is dropped.
     */
    fun adopt(values: Map<String, Map<String, String>>, registered: Set<String>)

    /** Forgets everything kept for [deviceId], as removing its registry row also does. */
    fun forget(deviceId: String)
}
