package tech.mmarca.openwatch.devices.blestandard

import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A radio with one device on the other end, scripted by the test: the characteristics it has,
 * what it answers a read with, and what it notifies or indicates once a characteristic is
 * switched on or written to. [drop] ends the link as a peripheral that walks away does.
 */
class FakeProfileRadio : ProfileRadio {

    /** The characteristics the device's GATT table has, by service. */
    val table = mutableMapOf<UUID, MutableSet<UUID>>()

    /** What a read of a characteristic answers. */
    val reads = mutableMapOf<UUID, ByteArray>()

    /** What the device sends once a characteristic is switched on, by that characteristic. */
    val onEnable = mutableMapOf<UUID, List<CharacteristicValue>>()

    /** What the device sends in answer to a write, by what was written. */
    var onWrite: (UUID, ByteArray) -> List<CharacteristicValue> = { _, _ -> emptyList() }

    /** Each link: the address, the owner it held the lease as, and what was switched on and written. */
    val links = mutableListOf<Session>()

    /** A dial fails with this, while set. */
    var failure: Exception? = null

    class Session(val address: String, val owner: String) {
        val enabled = mutableListOf<Pair<UUID, Boolean>>()
        val written = mutableListOf<Pair<UUID, ByteArray>>()
        val channel = Channel<CharacteristicValue>(Channel.UNLIMITED)
        var closed = false
    }

    fun has(service: UUID, vararg characteristics: UUID) {
        table.getOrPut(service) { mutableSetOf() } += characteristics
    }

    /** Sends [value] on [characteristic] over the open link. */
    fun send(characteristic: UUID, vararg value: Int) {
        links.last().channel.trySend(CharacteristicValue(characteristic, bytes(*value)))
    }

    /** The device walks away: the open link fails. */
    fun drop() {
        links.last().channel.close(IllegalStateException("link dropped"))
    }

    override suspend fun <T> withLink(address: String, owner: String, body: suspend (ProfileLink) -> T): T {
        failure?.let { throw it }
        val session = Session(address, owner)
        links += session
        try {
            return body(Link(session))
        } finally {
            session.closed = true
            session.channel.close()
        }
    }

    private inner class Link(private val session: Session) : ProfileLink {
        override val values: Flow<CharacteristicValue> = session.channel.receiveAsFlow()

        override fun has(service: UUID, characteristic: UUID): Boolean = table[service]?.contains(characteristic) == true

        override suspend fun enable(service: UUID, characteristic: UUID, indicate: Boolean) {
            check(has(service, characteristic)) { "no such characteristic" }
            session.enabled += characteristic to indicate
            onEnable[characteristic].orEmpty().forEach { session.channel.trySend(it) }
        }

        override suspend fun read(service: UUID, characteristic: UUID): ByteArray? =
            reads[characteristic].takeIf { has(service, characteristic) }

        override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray) {
            check(has(service, characteristic)) { "no such characteristic" }
            session.written += characteristic to value
            onWrite(characteristic, value).forEach { session.channel.trySend(it) }
        }
    }

    companion object {
        fun bytes(vararg value: Int) = ByteArray(value.size) { value[it].toByte() }

        fun value(characteristic: UUID, vararg value: Int) = CharacteristicValue(characteristic, bytes(*value))
    }
}
