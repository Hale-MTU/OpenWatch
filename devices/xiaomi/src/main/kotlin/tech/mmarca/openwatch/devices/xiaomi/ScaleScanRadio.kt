package tech.mmarca.openwatch.devices.xiaomi

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import tech.mmarca.openwatch.devices.core.ble.BleScanState
import tech.mmarca.openwatch.devices.core.ble.BleScanner

/** Whether the phone is listening for the scale, and if not, what stands in the way. */
enum class ScaleListenerStatus {
    /** No scale is set up. */
    OFF,
    LISTENING,
    BLUETOOTH_OFF,

    /** The Bluetooth scan permission is not granted. */
    PERMISSION_MISSING,

    /** No Bluetooth LE, or the system refused the scan. */
    UNAVAILABLE,
}

/**
 * The scan that hears the scale. A port, so everything deciding when to
 * listen is testable without a radio.
 */
interface ScaleScanRadio {

    /**
     * Asks the system to deliver the scale's broadcasts, narrowed to
     * [address] once it is known. [restart] replaces a scan already running.
     * Never throws.
     */
    fun arm(address: String?, restart: Boolean): ScaleListenerStatus

    fun disarm()
}

/**
 * The system scan handed a `PendingIntent` ([BleScanner.startBackground]): it outlives the
 * process, and the system starts [ScaleScanReceiver] for every broadcast that passes the
 * filter. Nothing here connects, so no radio lease is taken.
 *
 * The filter is matched in the Bluetooth chip, so a phone full of other `0xFE95` beacons
 * wakes nobody: the S400's service data, and its address once known.
 */
@Singleton
class SystemScaleScanRadio @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val scanner: BleScanner,
) : ScaleScanRadio {

    override fun arm(address: String?, restart: Boolean): ScaleListenerStatus =
        when (scanner.startBackground(S400Beacon.SERVICE_UUID, S400Beacon.scanFilters, address, pendingIntent(), restart)) {
            BleScanState.RUNNING -> ScaleListenerStatus.LISTENING
            BleScanState.BLUETOOTH_OFF -> ScaleListenerStatus.BLUETOOTH_OFF
            BleScanState.PERMISSION_MISSING -> ScaleListenerStatus.PERMISSION_MISSING
            BleScanState.UNAVAILABLE -> ScaleListenerStatus.UNAVAILABLE
        }

    override fun disarm() = scanner.stopBackground(pendingIntent())

    /** Explicit, so only this app's receiver gets it. Mutable, because the system adds the results. */
    private fun pendingIntent(): PendingIntent {
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ScaleScanReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or mutable,
        )
    }

    private companion object {
        const val REQUEST_CODE = 0x5400
    }
}
