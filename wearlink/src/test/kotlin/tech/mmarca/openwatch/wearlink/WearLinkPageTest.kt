package tech.mmarca.openwatch.wearlink

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A page's `END <count>` is checked against the lines that arrived, not the rows the phone kept. */
class WearLinkPageTest {

    /** A session whose watch answers with exactly [reply]. */
    private fun session(vararg reply: String): WearLinkSession {
        val input = ByteArrayInputStream(reply.joinToString("") { "$it\n" }.toByteArray())
        val connection = WearLinkConnection(input, ByteArrayOutputStream(), "AA:BB:CC:DD:EE:FF") {}
        return WearLinkSession(
            connection,
            WearLinkProtocol.HelloReply.Ok(WearLinkProtocol.VERSION, emptySet(), "watch"),
            LineReader(connection.input),
            LineWriter(connection.output),
        )
    }

    @Test
    fun `a page whose END matches its lines is read`() {
        val outcome = session("HR 1700000000000 61", "HR 1700000010000 62", "END 2 0").pullHeartRate(0, 10)

        val page = (outcome as WearLinkOutcome.Ok).value
        assertEquals(listOf(61, 62), page.items.map { it.bpm })
    }

    @Test
    fun `a page that lost a line on the way is refused, not taken as complete`() {
        val outcome = session("HR 1700000000000 61", "END 2 0").pullHeartRate(0, 10)

        assertTrue(outcome is WearLinkOutcome.ProtocolError)
    }

    @Test
    fun `a row the phone finds implausible still counts, so it does not stall the series`() {
        // 400 bpm fails the phone's plausibility check, but the watch sent it.
        val outcome = session("HR 1700000000000 61", "HR 1700000010000 400", "END 2 1").pullHeartRate(0, 10)

        val page = (outcome as WearLinkOutcome.Ok).value
        assertEquals(listOf(61), page.items.map { it.bpm })
        assertTrue(page.more)
    }
}
