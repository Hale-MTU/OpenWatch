package tech.mmarca.openwatch.devices.blestandard

/**
 * A measurement as the device sent it, kept until Health Connect has it: the characteristic
 * value's bytes as they came, with the phone's time and offset from UTC when they came, since a
 * profile's own time stamp is optional and has no zone. [context] is a glucose record's
 * Measurement Context, when the meter sent one. Kept as the bytes, so a record is read again by
 * the same parser the specification defines. Stored, so the format is final:
 * `raw1;<received millis>;<offset seconds>;<hex>;<context hex, or nothing>`.
 */
public data class ReceivedValue(
    public val receivedAtMillis: Long,
    public val zoneOffsetSeconds: Int,
    public val bytes: ByteArray,
    public val context: ByteArray? = null,
) {
    public fun encode(): String =
        "$VERSION;$receivedAtMillis;$zoneOffsetSeconds;${bytes.toHex()};${context?.toHex().orEmpty()}"

    override fun equals(other: Any?): Boolean = other is ReceivedValue &&
        receivedAtMillis == other.receivedAtMillis && zoneOffsetSeconds == other.zoneOffsetSeconds &&
        bytes.contentEquals(other.bytes) && context.contentEquals(other.context)

    override fun hashCode(): Int =
        ((receivedAtMillis.hashCode() * 31 + zoneOffsetSeconds) * 31 + bytes.contentHashCode()) * 31 + context.contentHashCode()

    public companion object {
        private const val VERSION = "raw1"

        /** [payload] read back, or null for one this version does not know or that is damaged. */
        public fun decode(payload: String): ReceivedValue? {
            val parts = payload.split(';')
            if (parts.size != 5 || parts[0] != VERSION) return null
            val received = parts[1].toLongOrNull() ?: return null
            val offset = parts[2].toIntOrNull() ?: return null
            val bytes = parts[3].fromHex()?.takeIf { it.isNotEmpty() } ?: return null
            val context = if (parts[4].isEmpty()) null else parts[4].fromHex() ?: return null
            return ReceivedValue(received, offset, bytes, context)
        }

        private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

        private fun String.fromHex(): ByteArray? {
            if (length % 2 != 0) return null
            return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toIntOrNull(16)?.toByte() ?: return null }
        }
    }
}
