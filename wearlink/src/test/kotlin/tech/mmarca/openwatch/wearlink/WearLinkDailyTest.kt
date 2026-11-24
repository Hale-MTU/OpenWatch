package tech.mmarca.openwatch.wearlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearLinkDailyTest {

    private val token = WearLinkToken.generate()

    @Test
    fun `a day round-trips, missing values included`() {
        val day = DaySummary(20_401, sleepMinutes = 432, awakeMinutes = 20, remMinutes = 90, lightMinutes = 250, deepMinutes = 72, restingBpm = 52)
        val line = WearLinkDaily.format(day)
        assertEquals("PUT_DAY 20401 432 20 90 250 72 52 - -", line)
        assertEquals(day, WearLinkDaily.parse(line))
        assertEquals(WearLinkProtocol.Request.PutDay(day), WearLinkProtocol.parseRequest(line))
        assertEquals(line, WearLinkProtocol.formatRequest(WearLinkProtocol.Request.PutDay(day)))
    }

    @Test
    fun `a day with an impossible value or too few fields is refused, a longer line is read`() {
        assertNull(WearLinkDaily.parse("PUT_DAY 20401 2000 - - - - - - -"))
        assertNull(WearLinkDaily.parse("PUT_DAY 20401 -5 - - - - - - -"))
        assertNull(WearLinkDaily.parse("PUT_DAY 20401 - - - - - 300 - -"))
        assertNull(WearLinkDaily.parse("PUT_DAY -1 - - - - - - - -"))
        assertNull(WearLinkDaily.parse("PUT_DAY 20401 432 - - - -"))
        assertNull(WearLinkDaily.parse("PUT_DAY 20401 7.5 - - - - - - -"))
        assertEquals(DaySummary(20_401, activeKcal = 300), WearLinkDaily.parse("PUT_DAY 20401 - - - - - - 300 - 7 later"))
    }

    @Test
    fun `the watch keeps a day the phone puts and answers DAY_OK`() {
        val loopback = Loopback().also { it.trust.tokens[it.phoneAddress] = token }
        val link = loopback.connect()
        val session = (WearLinkClient.hello(link.clientConnection, WearLinkProtocol.Hello(WearLinkProtocol.VERSION, emptySet(), token, "Pixel")) as WearLinkOutcome.Ok).value
        val day = DaySummary(20_401, sleepMinutes = 410, restingBpm = 54, activeKcal = 312, distanceMetres = 4022)

        assertEquals(WearLinkOutcome.Ok(Unit), session.putDay(day))
        assertEquals(mapOf(20_401L to day), loopback.handler.days)
        session.close()
    }
}
