package tech.mmarca.openwatch.devices.garmin

import java.time.LocalDate
import java.time.LocalDateTime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hydration widget's asks through the HTTP proxy, as a raw request and as a Connect IQ web
 * request, answered from what the log kept.
 */
class GarminHydrationInterceptorTest {

    /** The log in memory: each entry as logged, the day as the sum, with [goal]. */
    private class MemoryLog : GarminHydrationLog {
        val entries = mutableListOf<GarminHydrationEntry>()
        var kept: String? = null
        var goal = GarminHydrationDay.DEFAULT_GOAL_MILLILITERS

        override fun log(entry: GarminHydrationEntry) {
            entries += entry
        }

        override fun day(date: LocalDate): GarminHydrationDay {
            val ofDay = entries.filter { it.at.toLocalDate() == date }
            return GarminHydrationDay(ofDay.sumOf { it.milliliters }, ofDay.maxOfOrNull { it.at }, goal)
        }

        override fun settings(): String? = kept

        override fun keepSettings(json: String) {
            kept = json
        }
    }

    private val log = MemoryLog()
    private val proxy = GarminHttpProxy(listOf(GarminHydrationInterceptor(log), GarminOauthInterceptor()))

    private val summary = "https://connectapi.garmin.com/usersummary-service/usersummary/hydration"
    private val settings = "https://connectapi.garmin.com/userprofile-service/userprofile/user-settings/hydration"

    /** A raw request; the body in the text field, or in the bytes field when [bytesField]. */
    private fun raw(url: String, method: Int? = null, body: String? = null, bytesField: Boolean = false): ByteArray {
        val request = ProtobufWriter().string(1, url)
        if (method != null) request.varint(3, method)
        if (body != null) request.nested(if (bytesField) 7 else 4, body.toByteArray(Charsets.UTF_8))
        val service = ProtobufWriter().nested(5, request.toBytes()).toBytes()
        return ProtobufWriter().nested(GarminSmartService.HTTP, service).toBytes()
    }

    /** A Connect IQ web request: its body a Monkey C dictionary. */
    private fun connectIq(url: String, method: Int, body: JSONObject? = null, maxResponse: Int? = null): ByteArray {
        val request = ProtobufWriter().string(1, url).varint(2, method)
        request.nested(3, GarminMonkeyCData.encode(JSONObject().put("Content-Type", "application/json")))
        if (body != null) request.nested(4, GarminMonkeyCData.encode(body))
        if (maxResponse != null) request.varint(5, maxResponse)
        val service = ProtobufWriter().nested(1, request.toBytes()).toBytes()
        return ProtobufWriter().nested(GarminSmartService.HTTP, service).toBytes()
    }

    private fun service(reply: ByteArray?, field: Int): List<ProtobufField> {
        val http = protobufField(readProtobuf(reply!!), GarminSmartService.HTTP)!!.bytes!!
        return readProtobuf(protobufField(readProtobuf(http), field)!!.bytes!!)
    }

    private fun rawJson(reply: ByteArray?): JSONObject {
        val response = service(reply, 6)
        assertEquals(100L, protobufField(response, 1)!!.varint)
        return JSONObject(protobufField(response, 3)!!.bytes!!.toString(Charsets.UTF_8))
    }

    private fun rawStatus(reply: ByteArray?): Long? = protobufField(service(reply, 6), 1)?.varint

    private fun drink(at: String, milliliters: Double) =
        JSONObject().put("timestampLocal", at).put("valueInML", milliliters).toString()

    @Test
    fun `a drink logged on the watch is kept, and the day's total comes back with its goal`() {
        log.log(GarminHydrationEntry(LocalDateTime.parse("2026-11-28T08:00:00"), 500.0))
        log.goal = 2_750.0

        val answer = rawJson(proxy.handle(raw("$summary/log", GarminHttpMethod.PUT, drink("2026-11-28T9:05:07.25", 250.0))))

        assertEquals(GarminHydrationEntry(LocalDateTime.parse("2026-11-28T09:05:07.25"), 250.0), log.entries.last())
        assertEquals(750.0, answer.getDouble("valueInML"), 0.0)
        assertEquals("2026-11-28", answer.getString("calendarDate"))
        assertEquals("2026-11-28T09:05:07.2", answer.getString("lastEntryTimestampLocal"))
        assertEquals(2_750.0, answer.getDouble("goalInML"), 0.0)
    }

    @Test
    fun `a body in the bytes field reads the same`() {
        proxy.handle(raw("$summary/log", GarminHttpMethod.PUT, drink("2026-11-28T10:00:00", 330.0), bytesField = true))

        assertEquals(330.0, log.entries.single().milliliters, 0.0)
    }

    @Test
    fun `the day's summary has no last entry on a day with none`() {
        val answer = rawJson(proxy.handle(raw("$summary/daily/2026-11-27", GarminHttpMethod.GET)))

        assertEquals(0.0, answer.getDouble("valueInML"), 0.0)
        assertTrue(answer.isNull("lastEntryTimestampLocal"))
    }

    @Test
    fun `the settings are the defaults until the watch sends its own, then those`() {
        val defaults = rawJson(proxy.handle(raw(settings)))
        assertEquals("milliliter", defaults.getString("hydrationMeasurementUnit"))
        assertEquals(listOf(250, 500, 750), (0 until 3).map { defaults.getJSONArray("hydrationContainers").getJSONObject(it).getInt("volume") })

        val sent = JSONObject()
            .put("hydrationMeasurementUnit", "ounce")
            .put("hydrationContainers", org.json.JSONArray().put(JSONObject().put("volume", 16).put("unit", "ounce")))
            .put("valueInML", 1200.0)
        proxy.handle(raw("$summary/allData/2026-11-28", GarminHttpMethod.PUT, sent.toString()))
        val kept = rawJson(proxy.handle(raw(settings, GarminHttpMethod.GET)))

        assertEquals("ounce", kept.getString("hydrationMeasurementUnit"))
        assertEquals(16, kept.getJSONArray("hydrationContainers").getJSONObject(0).getInt("volume"))
        // The day's figures in that body are the watch's own, not settings.
        assertTrue("valueInML" !in JSONObject(log.kept!!).keys().asSequence().toList())
    }

    @Test
    fun `registering for the widget's pushes is acknowledged`() {
        assertEquals(0, rawJson(proxy.handle(raw("https://connectapi.garmin.com/pushnotification-service/ciq/hydration/register", GarminHttpMethod.PUT))).length())
    }

    @Test
    fun `a drink without its time or volume, a write to the summary, and another host are refused`() {
        assertEquals(0L, rawStatus(proxy.handle(raw("$summary/log", GarminHttpMethod.PUT, "{\"valueInML\":250}"))) ?: 0L)
        assertEquals(0L, rawStatus(proxy.handle(raw("$summary/log", GarminHttpMethod.PUT, drink("yesterday", 250.0)))) ?: 0L)
        assertEquals(0L, rawStatus(proxy.handle(raw("$summary/daily/2026-11-28", GarminHttpMethod.PUT))) ?: 0L)
        assertEquals(0L, rawStatus(proxy.handle(raw("https://example.com/usersummary-service/usersummary/hydration/log", GarminHttpMethod.PUT, drink("2026-11-28T10:00:00", 1.0)))) ?: 0L)
        assertTrue(log.entries.isEmpty())
    }

    @Test
    fun `a Connect IQ web request is answered in Monkey C values`() {
        val body = JSONObject().put("timestampLocal", "2026-11-28T12:00:00.0").put("valueInML", 500)

        val response = service(proxy.handle(connectIq("$summary/log", GarminHttpMethod.PUT, body, maxResponse = 4096)), 2)

        assertEquals(500.0, log.entries.single().milliliters, 0.0)
        assertEquals(100L, protobufField(response, 1)!!.varint)
        assertEquals(200L, protobufField(response, 2)!!.varint)
        val answer = GarminMonkeyCData.decode(protobufField(response, 3)!!.bytes!!) as JSONObject
        // A whole total goes as an integer, as Garmin's JSON reads back.
        assertEquals(500, answer.get("valueInML"))
        assertEquals("2026-11-28", answer.get("calendarDate"))
    }

    @Test
    fun `a Connect IQ answer past the app's limit says so`() {
        val response = service(proxy.handle(connectIq("$summary/daily/2026-11-28", GarminHttpMethod.GET, maxResponse = 10)), 2)

        assertEquals(402L, protobufField(response, 1)!!.varint)
        assertNull(protobufField(response, 3))
    }

    @Test
    fun `a Connect IQ request nothing claims gets an explicit unknown`() {
        val response = service(proxy.handle(connectIq("https://example.com/api", GarminHttpMethod.GET)), 2)

        assertEquals(0L, protobufField(response, 1)?.varint ?: 0L)
    }
}
