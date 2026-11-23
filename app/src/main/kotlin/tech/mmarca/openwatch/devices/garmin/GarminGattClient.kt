package tech.mmarca.openwatch.devices.garmin

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import tech.mmarca.openwatch.core.performance.runSuspendCatching
import tech.mmarca.openwatch.core.performance.DefaultDispatcherProvider
import tech.mmarca.openwatch.core.performance.DispatcherProvider
import tech.mmarca.openwatch.devices.core.ble.GattLink

/** Thrown when the watch cannot be reached or exposes no supported Garmin transport. */
class GarminGattClientException(message: String) : Exception(message)

/**
 * The Garmin stack's GATT client. It connects through [GattLink] (the dial, its redial, the
 * MTU, discovery, the CCCD and the writes, shared with every BLE device), finds the
 * multi-link characteristic pair and wires it to [GarminMlTransport]. A sync is bounded, so a
 * dropped link ends it, not redials. One instance drives one connection attempt. Every failure
 * is a [GarminGattClientException], the link's included.
 */
class GarminGattClient(
    private val context: Context,
    private val address: String,
    private val onLog: ((String) -> Unit)? = null,
    /** Ask for a large MTU. Off is the workaround for a watch that misbehaves with big packets. */
    private val highMtu: Boolean = true,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
) {

    private companion object {
        /** A bigger MTU is the largest factor in sync speed. */
        const val DESIRED_MTU = 515

        val CHANNEL_OPEN_TIMEOUT: Duration = 15.seconds
    }

    private var ml: GarminMlTransport? = null
    private var v1: GarminV1Transport? = null
    private var sendCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var receiveUuid: UUID? = null

    /** Runs the GFDI re-registration when the watch closes the handle. Invisible to callers. */
    private val healScope = CoroutineScope(SupervisorJob() + dispatchers.default)

    private val link = GattLink(
        context = context,
        address = address,
        // The link's timeouts are the ones measured on Garmin watches: its defaults.
        log = { message -> log("[GARMIN-BLE] $message") },
        failure = ::GarminGattClientException,
        onLost = { ml?.close() },
        onClosing = {
            healScope.cancel()
            ml?.close()
            ml = null
            v1 = null
            sendCharacteristic = null
            receiveUuid = null
        },
        onNotification = ::handleNotification,
    )

    /** Fires when the link drops mid-session, so the caller can abort cleanly. */
    val onDisconnected: SharedFlow<String> = link.onDisconnected

    private fun log(message: String) {
        GarminLog.log(message)
        onLog?.invoke(message)
    }

    private fun handleNotification(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        if (characteristic.uuid == receiveUuid) {
            val ml = ml
            if (ml != null) ml.handleInbound(value) else v1?.handleInbound(value)
        }
    }

    /**
     * Connects, opens the GFDI channel and returns the transport to send on.
     *
     * Prefers V2 multi-link when both variants exist and falls back to the
     * direct V1 characteristic pair.
     */
    suspend fun connect(
        onFrame: (GarminGfdiFrame) -> Unit,
        onRealtime: ((GarminRealtimeService, GarminRealtimeReading) -> Unit)? = null,
    ): GarminFrameTransport {
        val (services, mtu) = connectAndDiscover()
        findMlPair(services)?.let { (receive, send) -> return openV2(receive, send, mtu, onFrame, onRealtime) }
        findV1Pair(services)?.let { (receive, send) -> return openV1(receive, send, mtu, onFrame) }
        close()
        throw GarminGattClientException("No supported Garmin GFDI characteristics")
    }

    private suspend fun openV1(
        receive: BluetoothGattCharacteristic,
        send: BluetoothGattCharacteristic,
        mtu: Int,
        onFrame: (GarminGfdiFrame) -> Unit,
    ): GarminFrameTransport {
        val transport = GarminV1Transport(
            write = { packet -> writeToCharacteristic(packet) },
            onFrame = onFrame,
            onLog = ::log,
        )
        v1 = transport
        bind(receive, send, mtu, transport, label = "V1")
        return transport
    }

    private suspend fun openV2(
        receive: BluetoothGattCharacteristic,
        send: BluetoothGattCharacteristic,
        mtu: Int,
        onFrame: (GarminGfdiFrame) -> Unit,
        onRealtime: ((GarminRealtimeService, GarminRealtimeReading) -> Unit)?,
    ): GarminFrameTransport {
        val transport = GarminMlTransport(
            write = { packet -> writeToCharacteristic(packet) },
            onFrame = onFrame,
            onServiceData = { serviceCode, payload ->
                // Live readings are unframed and continuous; drop unparseable ones quietly.
                val service = GarminRealtimeParser.serviceFor(serviceCode)
                if (service != null) {
                    GarminRealtimeParser.parse(service, payload)?.let { reading ->
                        onRealtime?.invoke(service, reading)
                    }
                } else {
                    log("[GARMIN-ML] data on unclaimed service $serviceCode")
                }
            },
            onGfdiClosed = {
                // Re-register rather than tear down: on a held link this is recoverable.
                healScope.launch {
                    runSuspendCatching { ml?.reopenGfdi() }
                        .onFailure { log("[GARMIN-BLE] GFDI reopen failed: $it") }
                }
            },
            onControlPacket = { packet ->
                healScope.launch {
                    runSuspendCatching { writeToCharacteristic(packet) }
                        .onFailure { error ->
                            log("[GARMIN-BLE] control write failed: $error")
                            // Fail the open now rather than at its timeout.
                            ml?.failOpen(error)
                        }
                }
            },
            onLog = ::log,
        )
        ml = transport
        // Subscribed before the channel opens, or a fast response is missed.
        bind(receive, send, mtu, transport, label = "V2")
        try {
            transport.open()
            withTimeout(CHANNEL_OPEN_TIMEOUT) { transport.ready.await() }
        } catch (error: TimeoutCancellationException) {
            close()
            throw GarminGattClientException("Watch did not open the GFDI channel")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // A refused registration or a failed write, not a timeout.
            close()
            throw GarminGattClientException("Could not open the GFDI channel: ${error.message}")
        }
        return transport
    }

    /** Points the client at a characteristic pair, sizes [transport] to the MTU and subscribes. */
    private suspend fun bind(
        receive: BluetoothGattCharacteristic,
        send: BluetoothGattCharacteristic,
        mtu: Int,
        transport: GarminFrameTransport,
        label: String,
    ) {
        sendCharacteristic = send
        receiveUuid = receive.uuid
        log(
            "[GARMIN-BLE] using $label receive=${receive.uuid} send=${send.uuid} " +
                "mtu=$mtu writeType=${send.writeType}",
        )
        transport.onMtuChanged(mtu)
        try {
            link.subscribe(receive)
        } catch (error: Exception) {
            close()
            throw GarminGattClientException("Could not subscribe: ${error.message}")
        }
    }

    private fun findV1Pair(
        services: List<BluetoothGattService>,
    ): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        val characteristics = services.flatMap { it.characteristics }
            .associateBy { it.uuid.toString().lowercase() }
        val receive = characteristics[GarminUuids.GFDI_RECEIVE_V1]
        val send = characteristics[GarminUuids.GFDI_SEND_V1]
        return if (receive != null && send != null) receive to send else null
    }

    /** Connects, enumerates the GATT table and hangs up. The probe path: no GFDI traffic, no writes. */
    suspend fun enumerateServices(): List<GarminGattService> {
        val (services, _) = connectAndDiscover(requestMtu = false)
        return services.map { service ->
            GarminGattService(
                uuid = service.uuid.toString().lowercase(),
                characteristics = service.characteristics.associate { characteristic ->
                    characteristic.uuid.toString().lowercase() to
                        propertyNames(characteristic.properties)
                },
            )
        }
    }

    /** Property flags rendered the way the probe report logs them. */
    private fun propertyNames(properties: Int): List<String> = buildList {
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            add("writeNoRsp")
        }
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectAndDiscover(
        requestMtu: Boolean = true,
    ): Pair<List<BluetoothGattService>, Int> {
        val device = link.peripheral()
        // The bond is the only proof that this is the user's watch: the GFDI handshake
        // just echoes. Without it any device that borrows the address would be sent
        // notifications, calendar entries and the phone's position.
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            throw GarminGattClientException(
                "The watch is no longer paired with this phone. Remove it and add it again.",
            )
        }

        link.dial(device)

        // A refused MTU request just means smaller writes.
        var mtu = 23
        if (requestMtu && !highMtu) {
            log("[GARMIN-BLE] large packets are off for this watch; using the default MTU")
        } else if (requestMtu) {
            mtu = link.requestMtu(DESIRED_MTU)
        }

        return link.discoverServices() to mtu
    }

    /** The first receive/send pair in the handle window, as `CommunicatorV2` does. */
    private fun findMlPair(
        services: List<BluetoothGattService>,
    ): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        val byUuid = mutableMapOf<String, BluetoothGattCharacteristic>()
        for (service in services) {
            for (characteristic in service.characteristics) {
                byUuid[characteristic.uuid.toString().lowercase()] = characteristic
            }
        }
        for (handle in GarminUuids.ML_FIRST_RECEIVE_HANDLE..GarminUuids.ML_LAST_RECEIVE_HANDLE) {
            val receive = byUuid[GarminUuids.uuidForHandle(handle)]
            val send = byUuid[GarminUuids.uuidForHandle(handle + GarminUuids.ML_SEND_HANDLE_OFFSET)]
            if (receive != null && send != null) return receive to send
        }
        return null
    }

    private suspend fun writeToCharacteristic(packet: ByteArray) {
        val characteristic = sendCharacteristic
            ?: throw GarminGattClientException("Not connected")
        link.write(characteristic, packet)
    }

    /** Closes the link and releases everything. Idempotent. */
    fun close() {
        link.close()
    }
}
