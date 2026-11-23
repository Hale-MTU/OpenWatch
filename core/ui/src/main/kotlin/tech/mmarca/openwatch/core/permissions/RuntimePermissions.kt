package tech.mmarca.openwatch.core.permissions

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build

/**
 * The runtime permissions whose name depends on the Android release, chosen in one place.
 * Each takes the API level, defaulting to the device's, so a test can ask for both sides.
 */
// The names are inlined by the compiler, and sdkInt guards each one. Lint cannot see a guard on a parameter.
@SuppressLint("InlinedApi")
object RuntimePermissions {

    /** Android 12 (API 31) gave Bluetooth its own runtime grants. Before it, a BLE scan rides on location. */
    fun hasBluetoothGrants(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= Build.VERSION_CODES.S

    /** What a BLE scan needs: `BLUETOOTH_SCAN` from Android 12, `ACCESS_FINE_LOCATION` before it. */
    fun bluetoothScanPermission(sdkInt: Int = Build.VERSION.SDK_INT): String =
        if (hasBluetoothGrants(sdkInt)) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION

    /** What a connection to a bonded device needs: `BLUETOOTH_CONNECT` from Android 12, nothing before it. */
    fun bluetoothConnectPermission(sdkInt: Int = Build.VERSION.SDK_INT): String? =
        if (hasBluetoothGrants(sdkInt)) Manifest.permission.BLUETOOTH_CONNECT else null
}
