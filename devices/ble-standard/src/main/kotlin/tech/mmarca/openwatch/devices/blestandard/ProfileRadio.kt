package tech.mmarca.openwatch.devices.blestandard

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import tech.mmarca.openwatch.devices.core.DeviceLog
import tech.mmarca.openwatch.devices.core.RadioLeaseRegistry
import tech.mmarca.openwatch.devices.core.ble.GattLink
import tech.mmarca.openwatch.devices.core.ble.GattLinkException
import tech.mmarca.openwatch.devices.core.withRadioLease

/** One value a device notified or indicated, as it came. */
class CharacteristicValue(val characteristic: UUID, val bytes: ByteArray)

/** One open link to a standard-profile device, as the family reads it: no Android type in it, so a test fakes it. */
interface ProfileLink {

    /** Whether the device's GATT table has [characteristic] under [service]. */
    fun has(service: UUID, characteristic: UUID): Boolean

    /** Switches [characteristic]'s notifications, or its indications with [indicate], on. */
    suspend fun enable(service: UUID, characteristic: UUID, indicate: Boolean)

    /** [characteristic]'s value, or null when the device has none. */
    suspend fun read(service: UUID, characteristic: UUID): ByteArray?

    suspend fun write(service: UUID, characteristic: UUID, value: ByteArray)

    /** Every value notified or indicated, in order, once: it fails when the link drops. */
    val values: Flow<CharacteristicValue>
}

/** How the family opens a link: under the radio lease, for the length of [body]. A port, so the family is tested without a radio. */
interface ProfileRadio {

    /** Dials [address] holding its radio lease as [owner], runs [body] on the link, and closes it however [body] ends. */
    suspend fun <T> withLink(address: String, owner: String, body: suspend (ProfileLink) -> T): T
}

/** [ProfileRadio] over the shared [GattLink]: its dial and redial, discovery, the CCCD, reads and writes. */
@Singleton
class GattProfileRadio @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val leases: RadioLeaseRegistry,
) : ProfileRadio {

    private val log = DeviceLog("BleStandard")

    override suspend fun <T> withLink(address: String, owner: String, body: suspend (ProfileLink) -> T): T =
        leases.withRadioLease(address, owner) {
            // Unbounded: the binder thread must never wait on the family, and a link brings a few hundred values at most.
            val values = Channel<CharacteristicValue>(Channel.UNLIMITED)
            val gatt = GattLink(
                context = context,
                address = address,
                log = log::log,
                onNotification = { characteristic, value -> values.trySend(CharacteristicValue(characteristic.uuid, value.copyOf())) },
            )
            try {
                gatt.dial(gatt.peripheral())
                val services = gatt.discoverServices()
                coroutineScope {
                    val dropped = launch {
                        gatt.onDisconnected.first()
                        values.close(GattLinkException("link dropped"))
                    }
                    try {
                        body(Link(gatt, services, values))
                    } finally {
                        dropped.cancel()
                    }
                }
            } finally {
                gatt.close()
                values.close()
            }
        }

    private class Link(
        private val gatt: GattLink,
        private val services: List<BluetoothGattService>,
        channel: Channel<CharacteristicValue>,
    ) : ProfileLink {
        override val values: Flow<CharacteristicValue> = channel.receiveAsFlow()

        override fun has(service: UUID, characteristic: UUID): Boolean = find(service, characteristic) != null

        override suspend fun enable(service: UUID, characteristic: UUID, indicate: Boolean) {
            gatt.subscribe(require(service, characteristic), indicate)
        }

        override suspend fun read(service: UUID, characteristic: UUID): ByteArray? =
            find(service, characteristic)?.let { gatt.read(it) }

        override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray) {
            gatt.write(require(service, characteristic), value)
        }

        private fun find(service: UUID, characteristic: UUID): BluetoothGattCharacteristic? =
            services.firstOrNull { it.uuid == service }?.getCharacteristic(characteristic)

        private fun require(service: UUID, characteristic: UUID): BluetoothGattCharacteristic =
            find(service, characteristic) ?: throw GattLinkException("the device has no such characteristic")
    }
}
