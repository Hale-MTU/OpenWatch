package tech.mmarca.openwatch.core.permissions

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which grant Bluetooth takes on each side of Android 12. */
class RuntimePermissionsTest {

    @Test
    fun `from Android 12 a scan and a connection take Bluetooth's own grants`() {
        val android12 = Build.VERSION_CODES.S

        assertTrue(RuntimePermissions.hasBluetoothGrants(android12))
        assertEquals(Manifest.permission.BLUETOOTH_SCAN, RuntimePermissions.bluetoothScanPermission(android12))
        assertEquals(Manifest.permission.BLUETOOTH_CONNECT, RuntimePermissions.bluetoothConnectPermission(android12))
    }

    @Test
    fun `before Android 12 a scan rides on location and a connection needs nothing`() {
        val android11 = Build.VERSION_CODES.R

        assertFalse(RuntimePermissions.hasBluetoothGrants(android11))
        assertEquals(Manifest.permission.ACCESS_FINE_LOCATION, RuntimePermissions.bluetoothScanPermission(android11))
        assertNull(RuntimePermissions.bluetoothConnectPermission(android11))
    }
}
