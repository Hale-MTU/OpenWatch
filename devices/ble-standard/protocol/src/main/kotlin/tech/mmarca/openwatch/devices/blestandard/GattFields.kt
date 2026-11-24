package tech.mmarca.openwatch.devices.blestandard

/**
 * Reads a characteristic value field by field, as the Bluetooth SIG lays them out: little-endian,
 * each field after the one before. A field past the end is null, never a guess: a value is
 * untrusted until it parsed whole.
 */
internal class GattFields(private val bytes: ByteArray) {
    var offset: Int = 0
        private set

    val remaining: Int get() = bytes.size - offset

    fun uint8(): Int? = field(1) { bytes[offset].toInt() and 0xFF }

    fun uint16(): Int? = field(2) { (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8) }

    fun sint16(): Int? = uint16()?.let { if (it >= 0x8000) it - 0x10000 else it }

    /** Skips [count] bytes; false when fewer are left. */
    fun skip(count: Int): Boolean = field(count) { } != null

    private inline fun <T> field(size: Int, read: () -> T): T? {
        if (remaining < size) return null
        val value = read()
        offset += size
        return value
    }
}

/** Whether bit [bit] of [this] flags field is set. */
internal fun Int.hasBit(bit: Int): Boolean = this and (1 shl bit) != 0
