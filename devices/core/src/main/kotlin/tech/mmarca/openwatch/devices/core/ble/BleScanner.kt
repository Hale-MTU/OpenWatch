package tech.mmarca.openwatch.devices.core.ble

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import tech.mmarca.openwatch.devices.core.ServiceDataFilter

/** Whether a scan the system holds for a [PendingIntent] runs, and if not, what stands in the way. */
enum class BleScanState {
    RUNNING,
    BLUETOOTH_OFF,

    /** The Bluetooth scan permission is not granted. */
    PERMISSION_MISSING,

    /** No Bluetooth LE, or the system refused the scan. */
    UNAVAILABLE,
}

/**
 * The phone's BLE scanner, in the two ways the device layer listens. A scan opens no link, so
 * neither takes a radio lease. The scan grants are declared in the app's manifest, which this
 * module's lint does not see; the scans check them at run time.
 *
 * - In the foreground, a discovery scan with a callback, for a screen that lists what is
 *   advertising: [startDiscovery], [stopDiscovery].
 * - In the background, a scan the system runs for a [PendingIntent]: it outlives the process,
 *   and the system starts the intent's receiver for each advertisement that passes the
 *   filters, which the Bluetooth chip matches: [startBackground], [stopBackground].
 */
@Singleton
class BleScanner @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private fun adapter() = context.getSystemService(BluetoothManager::class.java)?.adapter

    /**
     * Starts a low-latency discovery scan reporting to [callback]. False when Bluetooth is off
     * or the system refused it; a revoked grant must not crash the screen that asked.
     */
    @SuppressLint("MissingPermission")
    fun startDiscovery(filters: List<ScanFilter>, callback: ScanCallback): Boolean {
        val adapter = adapter() ?: return false
        if (!adapter.isEnabled) return false
        return try {
            adapter.bluetoothLeScanner?.startScan(filters, LowLatency, callback)
            true
        } catch (error: SecurityException) {
            Log.w(TAG, "BLE scan refused: ${error.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery(callback: ScanCallback) {
        adapter()?.bluetoothLeScanner?.stopScan(callback)
    }

    /**
     * Asks the system to deliver every advertisement whose service data under [serviceUuid]
     * passes one of [filters] (from [address] only, once it is known) to [intent]. [restart]
     * replaces a scan already running. Never throws.
     */
    @SuppressLint("MissingPermission")
    fun startBackground(
        serviceUuid: String,
        filters: List<ServiceDataFilter>,
        address: String?,
        intent: PendingIntent,
        restart: Boolean,
    ): BleScanState {
        val adapter = adapter() ?: return BleScanState.UNAVAILABLE
        if (!hasBluetoothScanPermission(context)) return BleScanState.PERMISSION_MISSING
        if (!adapter.isEnabled) return BleScanState.BLUETOOTH_OFF
        val scanner = adapter.bluetoothLeScanner ?: return BleScanState.BLUETOOTH_OFF
        return try {
            // Before Android 10 a second start with the same intent adds a second scan.
            if (restart || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) scanner.stopScan(intent)
            val error = scanner.startScan(serviceDataFilters(serviceUuid, filters, address), LowLatency, intent)
            if (error == 0) {
                BleScanState.RUNNING
            } else {
                Log.w(TAG, "A background scan was refused: $error")
                BleScanState.UNAVAILABLE
            }
        } catch (error: SecurityException) {
            BleScanState.PERMISSION_MISSING
        } catch (error: IllegalStateException) {
            // Bluetooth went off between the check and the call.
            BleScanState.BLUETOOTH_OFF
        }
    }

    /** Stops the scan [intent] holds. Never throws. */
    @SuppressLint("MissingPermission")
    fun stopBackground(intent: PendingIntent) {
        try {
            adapter()?.takeIf { it.isEnabled }?.bluetoothLeScanner?.stopScan(intent)
        } catch (error: SecurityException) {
            // The permission is gone, and the system dropped the scan with it.
        } catch (error: IllegalStateException) {
            // Bluetooth is off: there is no scan to stop.
        }
    }

    private fun serviceDataFilters(
        serviceUuid: String,
        filters: List<ServiceDataFilter>,
        address: String?,
    ): List<ScanFilter> {
        val uuid = ParcelUuid.fromString(serviceUuid)
        return filters.map { filter ->
            ScanFilter.Builder()
                .setServiceData(uuid, filter.data, filter.mask)
                .apply { if (address != null) setDeviceAddress(address) }
                .build()
        }
    }

    private companion object {
        const val TAG = "BleScanner"

        /**
         * Asked for always, granted in full only while the app is on screen or holds a
         * foreground service: in the background the system scans a tenth of the time or less.
         */
        val LowLatency: ScanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
    }
}
