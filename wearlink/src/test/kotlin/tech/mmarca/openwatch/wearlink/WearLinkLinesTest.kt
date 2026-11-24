package tech.mmarca.openwatch.wearlink

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WearLinkLinesTest {

    @Test
    fun `lines round-trip, a trailing carriage return is dropped, the end is null`() {
        val out = ByteArrayOutputStream()
        LineWriter(out).apply {
            line("PING")
            line("SM 1 R 0 16 300 0 - - 0 0 0 1000 3 3 3 90 90 0")
            flush()
        }
        val reader = LineReader(ByteArrayInputStream(out.toByteArray() + "last\r\n".toByteArray()))

        assertEquals("PING", reader.readLine())
        assertEquals("SM 1 R 0 16 300 0 - - 0 0 0 1000 3 3 3 90 90 0", reader.readLine())
        assertEquals("last", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test
    fun `an unterminated final line is still returned`() {
        val reader = LineReader(ByteArrayInputStream("PONG".toByteArray()))

        assertEquals("PONG", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test
    fun `a line over the limit throws instead of growing`() {
        val reader = LineReader(ByteArrayInputStream(("x".repeat(600) + "\n").toByteArray()), maxLineLength = 512)

        assertThrows(LineTooLongException::class.java) { reader.readLine() }
    }

    @Test
    fun `multi-byte characters survive`() {
        val out = ByteArrayOutputStream()
        LineWriter(out).apply { line("OK 4 hr,sm Manu’s Watch8 ✓"); flush() }

        assertEquals("OK 4 hr,sm Manu’s Watch8 ✓", LineReader(ByteArrayInputStream(out.toByteArray())).readLine())
    }

    @Test
    fun `lines come out the same however the stream splits them, a character across a chunk included`() {
        val text = (List(300) { "SM 1700000000000 R 7200 16 300 0 58 12 6 0 0 1000 3 3 3 88 90 1" } + "OK 5 hr Manu’s Watch8 ✓" + "last\r")
            .joinToString("\n", postfix = "\n") + "tail"
        val expected = text.split("\n").map { it.trimEnd('\r') }

        for (step in listOf(1, 2, 3, 7, 4096, Int.MAX_VALUE)) {
            for (chunk in listOf(1, 5, 64, 4096)) {
                val reader = LineReader(Trickle(text.toByteArray(), step), chunkSize = chunk)
                assertEquals("step $step, chunk $chunk", expected, generateSequence { reader.readLine() }.toList())
                assertNull(reader.readLine())
            }
        }
    }

    @Test
    fun `a page is read from the stream a chunk at a time, not a byte per call`() {
        val page = List(2000) { "HR ${1_700_000_000_000L + it * 10_000L} 61" }.joinToString("\n", postfix = "\n")
        val stream = Trickle(page.toByteArray(), Int.MAX_VALUE)
        val reader = LineReader(stream)

        repeat(2000) { reader.readLine() }

        assertTrue("${stream.reads} reads for ${page.length} bytes", stream.reads <= page.length / 4096 + 1)
    }

    @Test
    fun `a line of exactly the limit reads, and one byte more throws even when it arrives in pieces`() {
        val reader = LineReader(Trickle(("x".repeat(512) + "\n" + "y".repeat(513) + "\n").toByteArray(), 100), maxLineLength = 512)

        assertEquals("x".repeat(512), reader.readLine())
        assertThrows(LineTooLongException::class.java) { reader.readLine() }
    }

    /** At most [step] bytes per read, as a socket hands over what has arrived; counts the reads. */
    private class Trickle(bytes: ByteArray, private val step: Int) : InputStream() {
        private val data = ByteArrayInputStream(bytes)
        var reads = 0
            private set

        override fun read(): Int {
            reads++
            return data.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            reads++
            return data.read(b, off, minOf(len, step))
        }
    }
}
