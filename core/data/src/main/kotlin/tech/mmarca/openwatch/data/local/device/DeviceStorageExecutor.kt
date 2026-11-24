package tech.mmarca.openwatch.data.local.device

import javax.inject.Qualifier

/**
 * The one thread the registry and the per-device state load and write on. One, so a state row
 * always reaches the table after its device's row: `device_state` has a foreign key on `devices`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DeviceStorageExecutor
