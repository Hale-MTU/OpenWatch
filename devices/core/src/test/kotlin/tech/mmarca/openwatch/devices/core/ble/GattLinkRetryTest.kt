package tech.mmarca.openwatch.devices.core.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A stack error on dialling is redialled after a pause; silence is not. */
class GattLinkRetryTest {

    private class OwnFailure(message: String) : Exception(message)

    private val peripheralAddress = "AA:BB:CC:DD:EE:FF"
    private val gatt = mockk<BluetoothGatt>(relaxed = true)
    private val device = mockk<BluetoothDevice> {
        every { bondState } returns BluetoothDevice.BOND_BONDED
    }
    private val adapter = mockk<BluetoothAdapter> {
        every { isEnabled } returns true
        every { getRemoteDevice(peripheralAddress) } returns device
    }
    private val context = mockk<Context> {
        every { getSystemService(Context.BLUETOOTH_SERVICE) } returns
            mockk<BluetoothManager> { every { this@mockk.adapter } returns this@GattLinkRetryTest.adapter }
    }

    /** Scripts what each dial reports, in order; a null entry is a dial nobody answers. */
    private fun scriptDials(scope: CoroutineScope, vararg outcomes: Int?) {
        val callback = slot<BluetoothGattCallback>()
        var dials = 0
        every { device.connectGatt(any(), any(), capture(callback), any<Int>()) } answers {
            val outcome = outcomes[dials++]
            if (outcome != null) {
                // As Android does: the report lands after connectGatt has returned.
                scope.launch {
                    val state = if (outcome == BluetoothGatt.GATT_SUCCESS) {
                        BluetoothProfile.STATE_CONNECTED
                    } else {
                        BluetoothProfile.STATE_DISCONNECTED
                    }
                    callback.captured.onConnectionStateChange(gatt, outcome, state)
                }
            }
            gatt
        }
    }

    private fun link() = GattLink(context, peripheralAddress, failure = ::OwnFailure)

    private suspend fun dialError(): Throwable? =
        runCatching { link().let { it.dial(it.peripheral()) } }.exceptionOrNull()

    @Test
    fun `a stack error is redialled, and the second dial carries on`() = runTest {
        scriptDials(this, 133, BluetoothGatt.GATT_SUCCESS)

        val error = dialError()

        assertEquals(null, error)
        verify(exactly = 2) { device.connectGatt(any(), any(), any(), any<Int>()) }
    }

    @Test
    fun `three stack errors end the dial, raised as the owner's own failure`() = runTest {
        scriptDials(this, 133, 133, 133)

        val error = dialError()

        assertTrue("got $error", error is OwnFailure)
        assertEquals("Could not connect: GATT connection failed (status 133)", error?.message)
        verify(exactly = 3) { device.connectGatt(any(), any(), any(), any<Int>()) }
    }

    @Test
    fun `a dial nobody answers is not redialled`() = runTest {
        scriptDials(this, null)

        val error = dialError()

        assertEquals("Could not connect", error?.message)
        verify(exactly = 1) { device.connectGatt(any(), any(), any(), any<Int>()) }
    }
}
