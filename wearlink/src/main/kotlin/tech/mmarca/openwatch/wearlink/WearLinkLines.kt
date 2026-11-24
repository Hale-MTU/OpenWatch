package tech.mmarca.openwatch.wearlink

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The framing of the link, defined once for both ends: UTF-8 text, one
 * message per line, `\n` terminated, a trailing `\r` tolerated. A line
 * longer than [maxLineLength] bytes is a protocol violation and ends the
 * connection rather than being buffered without bound.
 *
 * The stream is read a chunk at a time, whatever has arrived up to
 * [chunkSize] bytes, not a byte per call: a page of two thousand lines was
 * two hundred thousand reads of the socket. Bytes after the line returned
 * stay here for the next call, so one connection has one reader.
 */
class LineReader(
    private val input: InputStream,
    private val maxLineLength: Int = WearLinkProtocol.MAX_LINE_LENGTH,
    chunkSize: Int = CHUNK_SIZE,
) {
    private val line = ByteArray(maxLineLength)
    private val chunk = ByteArray(chunkSize)

    /** The next byte of [chunk] to hand out, and the end of what it holds. */
    private var position = 0
    private var limit = 0

    /** The next line without its terminator, or null at end of stream. */
    @Throws(IOException::class)
    fun readLine(): String? {
        var length = 0
        while (true) {
            if (position == limit) {
                val read = input.read(chunk, 0, chunk.size)
                if (read < 0) return if (length == 0) null else text(length)
                position = 0
                limit = read
                continue
            }
            val byte = chunk[position++]
            if (byte == NEWLINE) return text(length)
            if (length == maxLineLength) throw LineTooLongException(maxLineLength)
            line[length++] = byte
        }
    }

    private fun text(length: Int): String = String(line, 0, length, Charsets.UTF_8).trimEnd('\r')

    private companion object {
        /** A few lines of most series; the socket hands over what it has, up to this. */
        const val CHUNK_SIZE = 4096
        const val NEWLINE = '\n'.code.toByte()
    }
}

/** Writes lines; nothing reaches the peer until [flush]. */
class LineWriter(output: OutputStream) {
    private val out = output.bufferedWriter(Charsets.UTF_8)

    @Throws(IOException::class)
    fun line(text: String) {
        out.write(text)
        out.write("\n")
    }

    @Throws(IOException::class)
    fun flush() = out.flush()
}

class LineTooLongException(limit: Int) : IOException("line longer than $limit bytes")
