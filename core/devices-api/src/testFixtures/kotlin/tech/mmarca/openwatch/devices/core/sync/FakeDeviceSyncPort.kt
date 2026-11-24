package tech.mmarca.openwatch.devices.core.sync

import kotlinx.coroutines.CompletableDeferred
import tech.mmarca.openwatch.domain.model.BleSensorDevice

/**
 * Stands in for a whole integration's radio, protocol and import: answers [result], after
 * [progressToReport] and [gate], and remembers what it was asked. [canSync] says which devices
 * it claims, every device unless a test narrows it.
 */
class FakeDeviceSyncPort(
    private val canSync: (BleSensorDevice) -> Boolean = { true },
) : DeviceSyncPort {
    var result: DeviceSyncResult = DeviceSyncResult.Succeeded()
    var calls = 0
    var seenDevice: BleSensorDevice? = null
    var seenTrigger: SyncTrigger? = null

    /** Reported once through `onProgress` before the run ends; none when null. */
    var progressToReport: DeviceSyncProgress? = null

    /** Left incomplete by a test to hold a sync mid-flight. */
    var gate: CompletableDeferred<Unit>? = null

    /** Set by the test that breaks the never-throws contract on purpose. */
    var thrown: Exception? = null

    override fun canSync(device: BleSensorDevice): Boolean = canSync.invoke(device)

    override suspend fun sync(
        device: BleSensorDevice,
        trigger: SyncTrigger,
        onProgress: ((DeviceSyncProgress) -> Unit)?,
    ): DeviceSyncResult {
        calls++
        seenDevice = device
        seenTrigger = trigger
        progressToReport?.let { onProgress?.invoke(it) }
        gate?.await()
        thrown?.let { throw it }
        return result
    }
}
