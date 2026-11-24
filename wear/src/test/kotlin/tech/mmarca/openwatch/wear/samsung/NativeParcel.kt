package tech.mmarca.openwatch.wear.samsung

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A [ParcelInput] over bytes in the layout Android's native Parcel uses:
 * little-endian ints, longs and floats, and a string as its count of UTF-16
 * units, the units, a two-byte terminator and zero padding to four bytes. As
 * there, reading past the end gives 0 and a string that does not fit gives null.
 */
internal class NativeParcelBytes(private val bytes: ByteArray) : ParcelInput {

    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    override fun readInt(): Int = if (buffer.remaining() >= 4) buffer.int else 0.also { buffer.position(bytes.size) }

    override fun readLong(): Long = if (buffer.remaining() >= 8) buffer.long else 0L.also { buffer.position(bytes.size) }

    override fun readFloat(): Float = if (buffer.remaining() >= 4) buffer.float else 0f.also { buffer.position(bytes.size) }

    override fun readString(): String? {
        val units = readInt()
        if (units < 0) return null
        val withTerminator = (units.toLong() + 1) * 2
        if (withTerminator > buffer.remaining()) return null
        val start = buffer.position()
        val text = String(bytes, start, units * 2, Charsets.UTF_16LE)
        buffer.position(start + padded(withTerminator.toInt()))
        return text.takeIf { bytes[start + units * 2].toInt() == 0 && bytes[start + units * 2 + 1].toInt() == 0 }
    }

    override var position: Int
        get() = buffer.position()
        set(value) {
            buffer.position(value.coerceIn(0, bytes.size))
        }

    override val size: Int = bytes.size

    private fun padded(length: Int) = (length + 3) and 3.inv()
}

/** Writes a callback in the same layout, as the platform's `writeTypedList` of points and `writeList` of values would. */
internal object NativeCallbackWriter {

    private const val VAL_PARCELABLE = 4

    fun callback(points: List<Pair<Long, IntArray>>, lengthPrefixed: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        out.int(points.size)
        for ((epochMillis, ints) in points) {
            out.int(1)
            out.long(epochMillis)
            out.int(ints.size)
            for (value in ints) {
                val body = ByteArrayOutputStream().apply {
                    string(RecordedCallbacks.VALUE_CLASS)
                    int(value)
                    int(0) // the float, 0.0
                    long(0L)
                }.toByteArray()
                out.int(VAL_PARCELABLE)
                if (lengthPrefixed) out.int(body.size)
                out.write(body)
            }
        }
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.int(value: Int) = write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())

    private fun ByteArrayOutputStream.long(value: Long) = write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())

    private fun ByteArrayOutputStream.string(value: String) {
        int(value.length)
        val units = value.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)
        write(units)
        repeat((4 - units.size % 4) % 4) { write(0) }
    }
}
