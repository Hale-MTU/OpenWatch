package tech.mmarca.openwatch.devices.garmin

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import org.json.JSONArray
import org.json.JSONObject

/**
 * The serialised form a Connect IQ web request carries its headers and body in, and reads its
 * answer from: Monkey C's dictionaries, arrays, strings, numbers and booleans. A string table
 * comes first, then the values breadth first, a container's children after every value at its
 * own depth. Read into org.json values (a dictionary is a [JSONObject], an array a [JSONArray],
 * nothing [JSONObject.NULL]) and written from them. After Gadgetbridge's `GarminJson`.
 */
internal object GarminMonkeyCData {

    private val STRINGS_MAGIC = byteArrayOf(0xab.toByte(), 0xcd.toByte(), 0xab.toByte(), 0xcd.toByte())
    private val DATA_MAGIC = byteArrayOf(0xda.toByte(), 0x7a, 0xda.toByte(), 0x7a)

    private const val TYPE_NULL = 0x00
    private const val TYPE_SINT32 = 0x01
    private const val TYPE_FLOAT = 0x02
    private const val TYPE_STRING = 0x03
    private const val TYPE_ARRAY = 0x05
    private const val TYPE_BOOL = 0x09
    private const val TYPE_MAP = 0x0b
    private const val TYPE_SINT64 = 0x0e
    private const val TYPE_DOUBLE = 0x0f
    private const val TYPE_CHAR = 0x13
    private const val TYPE_BYTE_ARRAY = 0x14

    /** A container read before its children, which follow once its depth is done. */
    private class Pending(val container: Any, val size: Int)

    /** The value in [bytes]. Throws [IllegalArgumentException] for bytes that are not this form. */
    fun decode(bytes: ByteArray): Any = try {
        decodeOrThrow(ByteBuffer.wrap(bytes))
    } catch (error: BufferUnderflowException) {
        throw IllegalArgumentException("serialised value cut short", error)
    }

    private fun decodeOrThrow(buffer: ByteBuffer): Any {
        val strings = HashMap<Int, String>()
        var magic = ByteArray(4).also(buffer::get)
        if (magic.contentEquals(STRINGS_MAGIC)) {
            val length = buffer.int
            val start = buffer.position()
            require(length >= 0 && start + length <= buffer.limit()) { "string table runs past the end" }
            while (buffer.position() < start + length) {
                val offset = buffer.position() - start
                // The length counts the terminating zero.
                val size = buffer.short.toInt() and 0xFFFF
                require(size >= 1) { "a string without its terminator" }
                val text = ByteArray(size - 1).also(buffer::get)
                buffer.get()
                strings[offset] = text.toString(Charsets.UTF_8)
            }
            magic = ByteArray(4).also(buffer::get)
        }
        require(magic.contentEquals(DATA_MAGIC)) { "no data section" }
        val length = buffer.int
        require(length >= 0 && buffer.position() + length <= buffer.limit()) { "data section runs past the end" }

        val root = value(buffer, strings)
        val queue = ArrayDeque<Pending>()
        if (root is Pending) queue.addLast(root)
        while (queue.isNotEmpty()) {
            val pending = queue.removeFirst()
            repeat(pending.size) {
                val container = pending.container
                if (container is JSONObject) {
                    val key = value(buffer, strings)
                    require(key !is Pending) { "a dictionary key is a container" }
                    container.put(key.toString(), unwrap(value(buffer, strings), queue))
                } else {
                    (container as JSONArray).put(unwrap(value(buffer, strings), queue))
                }
            }
        }
        return if (root is Pending) root.container else root
    }

    /** A child as its parent holds it; a container's own children wait their turn. */
    private fun unwrap(value: Any, queue: ArrayDeque<Pending>): Any =
        if (value is Pending) value.container.also { queue.addLast(value) } else value

    private fun value(buffer: ByteBuffer, strings: Map<Int, String>): Any =
        when (val type = buffer.get().toInt() and 0xFF) {
            TYPE_NULL -> JSONObject.NULL
            TYPE_BOOL -> buffer.get().toInt() != 0
            TYPE_SINT32 -> buffer.int
            TYPE_SINT64 -> buffer.long
            // Through its shortest text, so 0.3 reads as 0.3, not as the float's exact double.
            TYPE_FLOAT -> finite(buffer.float.toString().toDouble())
            TYPE_DOUBLE -> finite(buffer.double)
            TYPE_STRING -> buffer.int.let { offset -> requireNotNull(strings[offset]) { "no string at $offset" } }
            // Four bytes, the upper two always zero: one UTF-16 unit.
            TYPE_CHAR -> (buffer.int and 0xFFFF).toChar().toString()
            TYPE_BYTE_ARRAY -> JSONArray().also { array -> repeat(count(buffer)) { array.put(buffer.get().toInt()) } }
            TYPE_ARRAY -> Pending(JSONArray(), count(buffer))
            TYPE_MAP -> Pending(JSONObject(), count(buffer))
            else -> throw IllegalArgumentException("unknown value type $type")
        }

    private fun count(buffer: ByteBuffer): Int = buffer.int.also { require(it >= 0) { "a negative count" } }

    /** org.json holds no NaN or infinity; such a number reads as nothing. */
    private fun finite(value: Double): Any = if (value.isFinite()) value else JSONObject.NULL

    /** [value] serialised: an org.json value, a string, a number, a boolean, or null. */
    fun encode(value: Any?): ByteArray {
        val offsets = LinkedHashMap<String, Int>()
        val table = ByteArrayOutputStream()
        val tableOut = DataOutputStream(table)
        for (text in stringsOf(value)) {
            offsets[text] = table.size()
            val utf8 = text.toByteArray(Charsets.UTF_8)
            tableOut.writeShort(utf8.size + 1)
            tableOut.write(utf8)
            tableOut.writeByte(0)
        }

        val data = ByteArrayOutputStream()
        val dataOut = DataOutputStream(data)
        val queue = ArrayDeque<Any?>().apply { addLast(value) }
        while (queue.isNotEmpty()) {
            writeValue(dataOut, queue.removeFirst(), offsets, queue)
        }

        val out = ByteArrayOutputStream()
        val framed = DataOutputStream(out)
        if (table.size() > 0) {
            framed.write(STRINGS_MAGIC)
            framed.writeInt(table.size())
            framed.write(table.toByteArray())
        }
        framed.write(DATA_MAGIC)
        framed.writeInt(data.size())
        framed.write(data.toByteArray())
        return out.toByteArray()
    }

    /** Every string [root] holds, keys included, each once. */
    private fun stringsOf(root: Any?): Set<String> {
        val strings = LinkedHashSet<String>()
        val queue = ArrayDeque<Any?>().apply { addLast(root) }
        while (queue.isNotEmpty()) {
            when (val value = queue.removeFirst()) {
                is String -> strings += value
                is JSONObject -> value.keys().forEach { key ->
                    strings += key
                    queue.addLast(value.opt(key))
                }
                is JSONArray -> for (index in 0 until value.length()) queue.addLast(value.opt(index))
            }
        }
        return strings
    }

    private fun writeValue(out: DataOutputStream, value: Any?, offsets: Map<String, Int>, queue: ArrayDeque<Any?>) {
        when (value) {
            null, JSONObject.NULL -> out.writeByte(TYPE_NULL)
            is Boolean -> {
                out.writeByte(TYPE_BOOL)
                out.writeByte(if (value) 1 else 0)
            }
            is String -> {
                out.writeByte(TYPE_STRING)
                out.writeInt(offsets.getValue(value))
            }
            is Number -> writeNumber(out, value)
            is JSONArray -> {
                out.writeByte(TYPE_ARRAY)
                out.writeInt(value.length())
                for (index in 0 until value.length()) queue.addLast(value.opt(index))
            }
            is JSONObject -> {
                val keys = value.keys().asSequence().toList()
                out.writeByte(TYPE_MAP)
                out.writeInt(keys.size)
                for (key in keys) {
                    queue.addLast(key)
                    queue.addLast(value.opt(key))
                }
            }
            else -> throw IllegalArgumentException("cannot serialise a ${value::class.simpleName}")
        }
    }

    /**
     * A whole number as a 32-bit integer where it fits and a 64-bit one where not; any other as
     * a float where a float holds it exactly, else as a double. A whole 250.0 goes as 250, as a
     * JSON text read back would give it.
     */
    private fun writeNumber(out: DataOutputStream, value: Number) {
        val whole: Long? = when (value) {
            is Int, is Long, is Short, is Byte -> value.toLong()
            else -> value.toDouble().takeIf { it.isFinite() && it == Math.rint(it) && kotlin.math.abs(it) < LONG_LIMIT }?.toLong()
        }
        if (whole != null) {
            if (whole >= Int.MIN_VALUE && whole <= Int.MAX_VALUE) {
                out.writeByte(TYPE_SINT32)
                out.writeInt(whole.toInt())
            } else {
                out.writeByte(TYPE_SINT64)
                out.writeLong(whole)
            }
            return
        }
        val double = value.toDouble()
        if (kotlin.math.abs(double) < Float.MAX_VALUE && double.toFloat().toDouble() == double) {
            out.writeByte(TYPE_FLOAT)
            out.writeFloat(double.toFloat())
        } else {
            out.writeByte(TYPE_DOUBLE)
            out.writeDouble(double)
        }
    }

    /** Past this a double's whole part no longer fits a Long. */
    private const val LONG_LIMIT = 9.2e18
}
