package tech.mmarca.openwatch.devices.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import tech.mmarca.openwatch.core.performance.runSuspendCatching

/** What a [GattLink] raises when its owner names no exception of its own. */
class GattLinkException(message: String) : Exception(message)

/**
 * One GATT connection to one peripheral, as coroutines: the dial and its redial policy, the
 * MTU, service discovery, notifications switched on through the CCCD, and writes, one at a
 * time, with the characteristic's own write type. The only file in the device layer that
 * dials a GATT link (`connectGatt`), so the policy is written once.
 *
 * It takes no radio lease: its owner holds one ([tech.mmarca.openwatch.devices.core.withRadioLease])
 * for the whole life of the link, from [dial] to [close]. It decides nothing about the
 * peripheral (a bond, a protocol): the owner does, between the steps. One instance drives one
 * connection; after [close] it is done.
 */
class GattLink(
    private val context: Context,
    private val address: String,
    private val timeouts: Timeouts = Timeouts(),
    /** Each step's diagnostic, without a prefix; the owner tags it. */
    private val log: (String) -> Unit = {},
    /** The exception every failure is raised as, so the owner's callers keep catching its own type. */
    private val failure: (String) -> Exception = ::GattLinkException,
    /** Told when the link fails or drops, before any waiter is failed. */
    private val onLost: () -> Unit = {},
    /** Told at the start of [close], once the link counts as closed and before it hangs up. */
    private val onClosing: () -> Unit = {},
    /** Every notification the peripheral sends, on the binder thread it arrives on. */
    private val onNotification: (BluetoothGattCharacteristic, ByteArray) -> Unit = { _, _ -> },
) {

    /** How long each step may take. The defaults are the Garmin link's, measured on its watches. */
    data class Timeouts(
        /** Long, because a connect right after bonding may find the peripheral still settling. */
        val connect: Duration = 20.seconds,
        /** Dials before a stack error is final, and the pause that lets the stack settle between them. */
        val connectAttempts: Int = 3,
        val connectSettle: Duration = 2.seconds,
        val discover: Duration = 10.seconds,
        val mtu: Duration = 5.seconds,
        val subscribe: Duration = 5.seconds,
        val write: Duration = 5.seconds,
    )

    private var gatt: BluetoothGatt? = null

    @Volatile
    private var closed = false

    /** Fires when the link drops after it was up, so the owner can abort cleanly. Not after [close]. */
    private val disconnected = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val onDisconnected: SharedFlow<String> = disconnected

    // Single-shot bridges from the GATT callback into coroutines. Fresh per dial: a failed one leaves them failed.
    @Volatile
    private var connectedSignal = CompletableDeferred<Unit>()

    @Volatile
    private var servicesSignal = CompletableDeferred<List<BluetoothGattService>>()

    @Volatile
    private var mtuSignal = CompletableDeferred<Int>()

    /** True once a dial got through. Only then is a drop news to the owner. */
    @Volatile
    private var linkUp = false

    @Volatile
    private var descriptorWritten: CompletableDeferred<Unit>? = null

    @Volatile
    private var writeCompleted: CompletableDeferred<Unit>? = null

    /** Android GATT is one-operation-at-a-time; writes are serialised here. */
    private val writeMutex = Mutex()

    private val callback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            // A hung-up dial can still report; only the current link counts.
            if (gatt !== this@GattLink.gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val failed = failure("GATT connection failed (status $status)")
                onLost()
                connectedSignal.completeExceptionally(failed)
                servicesSignal.completeExceptionally(failed)
                descriptorWritten?.completeExceptionally(failed)
                writeCompleted?.completeExceptionally(failed)
                if (linkUp && !closed) disconnected.tryEmit("GATT status $status")
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    linkUp = true
                    connectedSignal.complete(Unit)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (linkUp && !closed) {
                        log("link dropped")
                        disconnected.tryEmit("link dropped")
                    }
                    onLost()
                    val gone = failure("Link dropped")
                    connectedSignal.completeExceptionally(gone)
                    servicesSignal.completeExceptionally(gone)
                    descriptorWritten?.completeExceptionally(gone)
                    writeCompleted?.completeExceptionally(gone)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            mtuSignal.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_MTU)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                servicesSignal.complete(gatt.services.orEmpty())
            } else {
                servicesSignal.completeExceptionally(failure("Service discovery failed (status $status)"))
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val waiter = descriptorWritten ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                waiter.complete(Unit)
            } else {
                waiter.completeExceptionally(failure("Descriptor write failed (status $status)"))
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val waiter = writeCompleted ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                waiter.complete(Unit)
            } else {
                waiter.completeExceptionally(failure("Characteristic write failed (status $status)"))
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onNotification(characteristic, value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onNotification(
                characteristic,
                @Suppress("DEPRECATION")
                characteristic.value ?: byteArrayOf(),
            )
        }
    }

    /** The peripheral at the link's address. Fails while Bluetooth is off or for an address that is not one. */
    fun peripheral(): BluetoothDevice {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) throw failure("Bluetooth is unavailable")
        return runCatching { adapter.getRemoteDevice(address) }.getOrNull()
            ?: throw failure("Invalid device address")
    }

    /**
     * Connects, redialling after a settle pause when the stack reports an error (133 and its
     * kin), which a fresh dial usually clears. No answer at all is not retried: the peripheral
     * is away, and another wait would be as long again. A failure closes the link.
     */
    @SuppressLint("MissingPermission")
    suspend fun dial(device: BluetoothDevice) {
        var lastFailure: String? = null
        for (attempt in 1..timeouts.connectAttempts) {
            connectedSignal = CompletableDeferred()
            servicesSignal = CompletableDeferred()
            mtuSignal = CompletableDeferred()
            val dialled = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            gatt = dialled
            val outcome = withTimeoutOrNull(timeouts.connect) {
                runSuspendCatching { connectedSignal.await() }
            }
            if (outcome?.isSuccess == true) return
            hangUp(dialled)
            if (outcome == null) {
                close()
                throw failure("Could not connect")
            }
            lastFailure = outcome.exceptionOrNull()?.message
            if (attempt == timeouts.connectAttempts) break
            log(
                "connect attempt $attempt failed ($lastFailure); " +
                    "redialling in ${timeouts.connectSettle.inWholeSeconds}s",
            )
            delay(timeouts.connectSettle)
        }
        close()
        throw failure("Could not connect: $lastFailure")
    }

    /** Asks for [mtu] and returns what the peripheral agreed to. A refusal just means smaller writes. */
    @SuppressLint("MissingPermission")
    suspend fun requestMtu(mtu: Int): Int {
        val requested = runCatching { gatt?.requestMtu(mtu) == true }.getOrDefault(false)
        if (!requested) {
            log("MTU request failed, using default")
            return DEFAULT_MTU
        }
        return withTimeoutOrNull(timeouts.mtu) { mtuSignal.await() } ?: DEFAULT_MTU
    }

    /** The peripheral's GATT table. A failure closes the link. */
    @SuppressLint("MissingPermission")
    suspend fun discoverServices(): List<BluetoothGattService> {
        if (gatt?.discoverServices() != true) {
            close()
            throw failure("Could not start service discovery")
        }
        val services = withTimeoutOrNull(timeouts.discover) {
            runSuspendCatching { servicesSignal.await() }.getOrNull()
        }
        if (services == null) {
            close()
            throw failure("Service discovery failed")
        }
        return services
    }

    /** Switches [characteristic]'s notifications on, CCCD included, and waits for the peripheral to confirm. */
    @SuppressLint("MissingPermission")
    suspend fun subscribe(characteristic: BluetoothGattCharacteristic) {
        val currentGatt = gatt ?: throw failure("Not connected")
        if (!currentGatt.setCharacteristicNotification(characteristic, true)) {
            throw failure("setCharacteristicNotification refused")
        }
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
            ?: throw failure("No CCCD on the receive characteristic")
        val waiter = CompletableDeferred<Unit>()
        descriptorWritten = waiter
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            currentGatt.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                currentGatt.writeDescriptor(descriptor)
            }
        }
        if (!started) throw failure("Descriptor write refused")
        val outcome = withTimeoutOrNull(timeouts.subscribe) { waiter.await() }
        descriptorWritten = null
        if (outcome == null) throw failure("Descriptor write timed out")
    }

    /**
     * Writes [packet] to [characteristic] with the characteristic's own write type: with a
     * response when the peripheral offers one, so each chunk is confirmed at the ATT layer.
     * Without one, a chunk the peripheral dropped was never known, and the only pacing was a
     * callback some stacks skip.
     */
    @SuppressLint("MissingPermission")
    suspend fun write(characteristic: BluetoothGattCharacteristic, packet: ByteArray) {
        val currentGatt = gatt ?: throw failure("Not connected")
        writeMutex.withLock {
            val completion = CompletableDeferred<Unit>()
            writeCompleted = completion
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                currentGatt.writeCharacteristic(
                    characteristic,
                    packet,
                    characteristic.writeType,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    characteristic.value = packet
                    currentGatt.writeCharacteristic(characteristic)
                }
            }
            if (!started) {
                writeCompleted = null
                throw failure("Characteristic write failed")
            }
            // Paced, not required: some stacks omit the callback for a write without response.
            // An explicit failure is still an error.
            val outcome = withTimeoutOrNull(timeouts.write) {
                runSuspendCatching { completion.await() }
            }
            writeCompleted = null
            outcome?.getOrThrow()
        }
    }

    /** Drops a dial that failed, without marking the link closed, so it can redial. */
    @SuppressLint("MissingPermission")
    private fun hangUp(dialled: BluetoothGatt) {
        gatt = null
        runCatching { dialled.disconnect() }
        runCatching { dialled.close() }
    }

    /** Closes the link and releases everything. Idempotent. */
    @SuppressLint("MissingPermission")
    fun close() {
        closed = true
        onClosing()
        gatt?.let { currentGatt ->
            runCatching { currentGatt.disconnect() }
            runCatching { currentGatt.close() }
        }
        gatt = null
    }

    private companion object {
        /** What every link gets without asking, and what a refused or unanswered MTU request leaves. */
        const val DEFAULT_MTU = 23

        /** The standard CCCD, needed to switch a characteristic's notifications on. */
        val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
