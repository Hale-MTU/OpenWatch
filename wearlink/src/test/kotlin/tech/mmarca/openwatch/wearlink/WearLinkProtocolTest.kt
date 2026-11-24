package tech.mmarca.openwatch.wearlink

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearLinkProtocolTest {

    @Test
    fun `the service record is not the one OpenVitals listens on`() {
        // Both watch apps can sit on one watch; each phone app must find only its own.
        assertNotEquals(
            UUID.fromString("4838d728-6e5a-4b95-a29d-a60032338301"),
            WearLinkProtocol.SERVICE_UUID,
        )
    }

    @Test
    fun `the version 4 requests are gone, so a version 4 phone's request is a bad one`() {
        for (word in listOf("HR_SINCE", "SM_SINCE", "ST_SINCE", "AS_SINCE", "HV_SINCE", "RR_SINCE", "SK_SINCE", "O2_SINCE")) {
            assertNull(word, WearLinkProtocol.parseRequest("$word 1700000000000 2000"))
        }
    }

    @Test
    fun `a sample and the end line round-trip`() {
        assertEquals(
            WearLinkProtocol.HeartRateSample(1_700_000_000_000L, 64),
            WearLinkProtocol.parseSample(WearLinkProtocol.formatSample(1_700_000_000_000L, 64)),
        )
        assertEquals(WearLinkProtocol.End(12, true), WearLinkProtocol.parseEnd(WearLinkProtocol.formatEnd(12, true)))
        assertEquals(WearLinkProtocol.End(0, false), WearLinkProtocol.parseEnd("END 0 0"))
    }

    @Test
    fun `malformed lines parse to null instead of throwing`() {
        assertNull(WearLinkProtocol.parseSample("HR 123"))
        assertNull(WearLinkProtocol.parseSample("HR abc 70"))
        assertNull(WearLinkProtocol.parseSample("HR 1700000000000 0"))
        assertNull(WearLinkProtocol.parseEnd("END 3 maybe"))
        assertNull(WearLinkProtocol.parseRequest("WO_SINCE -1 10"))
        assertNull(WearLinkProtocol.parseRequest("WO_SINCE 10"))
    }

    @Test
    fun `a series request round-trips, clamps its limit and refuses what is not a key or a cursor`() {
        val request = WearLinkProtocol.Request.SeriesSince("hv", "9f3a6b2c1d4e5f60.1234", 99_999)
        val line = WearLinkProtocol.formatRequest(request)

        assertEquals("SINCE hv 9f3a6b2c1d4e5f60.1234 2000", line)
        assertEquals(request.copy(limit = WearLinkSince.MAX_LIMIT), WearLinkProtocol.parseRequest(line))
        assertEquals(
            WearLinkProtocol.Request.SeriesSince("o2", "t1700000000000", 500),
            WearLinkProtocol.parseRequest("SINCE o2 ${WearLinkSince.afterTime(1_700_000_000_000L)} 500"),
        )
        assertNull(WearLinkProtocol.parseRequest("SINCE hv t0"))
        assertNull(WearLinkProtocol.parseRequest("SINCE hv t0 0"))
        assertNull(WearLinkProtocol.parseRequest("SINCE h,v t0 10"))
        assertNull(WearLinkProtocol.parseRequest("SINCE hv a:b 10"))
        assertNull(WearLinkProtocol.parseRequest("SINCE hv ${"1".repeat(41)} 10"))
        assertNull(WearLinkProtocol.parseRequest("SINCE hv t0 10 more"))
    }

    @Test
    fun `a series reply ends with its cursor, and a cursor points into the watch's own store or before its first row`() {
        assertEquals(WearLinkSince.End(2, true, "ab.7"), WearLinkSince.parseEnd(WearLinkSince.formatEnd(2, true, "ab.7")))
        assertEquals(WearLinkSince.End(0, false, "ab.7"), WearLinkSince.parseEnd("END 0 0 ab.7 future"))
        assertNull(WearLinkSince.parseEnd("END 2 1"))
        assertNull(WearLinkSince.parseEnd("END 2 1 a:b"))

        val store = "9f3a6b2c1d4e5f60"
        val cursor = WearLinkSince.cursor(store, 1234)
        assertEquals("9f3a6b2c1d4e5f60.1234", cursor)
        assertEquals(WearLinkSince.Position.AfterSeq(1234), WearLinkSince.position(cursor, store))
        assertEquals(WearLinkSince.Position.AfterTime(1_700_000_000_000L), WearLinkSince.position("t1700000000000", store))
        // A time before the epoch is the epoch: everything the watch keeps.
        assertEquals("t0", WearLinkSince.afterTime(-5))
        // Another store, or anything the watch did not write: before the first row, never past one.
        assertEquals(WearLinkSince.Position.AfterSeq(0), WearLinkSince.position(cursor, "0000000000000001"))
        assertEquals(WearLinkSince.Position.AfterSeq(0), WearLinkSince.position("$store.x", store))
        assertEquals(WearLinkSince.Position.AfterSeq(0), WearLinkSince.position("$store.-4", store))
        assertEquals(WearLinkSince.Position.AfterSeq(0), WearLinkSince.position("tomorrow", store))
    }

    @Test
    fun `a stored line goes out only whole and within the limit`() {
        assertTrue(WearLinkSince.isSendable("HV 1700000000000 452 280"))
        assertFalse(WearLinkSince.isSendable("HV 1\nEND 0 0"))
        assertFalse(WearLinkSince.isSendable("HV 1\r"))
        assertFalse(WearLinkSince.isSendable(" "))
        assertFalse(WearLinkSince.isSendable("HV " + "1".repeat(WearLinkProtocol.MAX_LINE_LENGTH)))
    }

    private fun minute(bpm: Int?, kind: WearLinkProtocol.MinuteKind = WearLinkProtocol.MinuteKind.RAW) =
        WearLinkProtocol.SleepMinute(
            epochMillis = 1_700_000_000_000L,
            kind = kind,
            offsetSeconds = 7200,
            flags = 16,
            sampleCount = 300,
            movement10 = 42,
            bpm = bpm,
            heartRateSd10 = if (bpm == null) null else 15,
            heartRateSamples = if (bpm == null) 0 else 6,
            meanMilliG = intArrayOf(-12, 3, 998),
            sdMilliG = intArrayOf(3, 4, 5),
            zAngleMin = 86,
            zAngleMax = 90,
            zAngleDelta10 = 7,
        )

    @Test
    fun `a sleep minute round-trips, with and without a heart rate`() {
        val raw = minute(58)
        val unworn = minute(null, WearLinkProtocol.MinuteKind.UNMEASURABLE)

        assertEquals("SM 1700000000000 R 7200 16 300 42 58 15 6 -12 3 998 3 4 5 86 90 7", WearLinkProtocol.formatSleepMinute(raw))
        assertEquals(raw, WearLinkProtocol.parseSleepMinute(WearLinkProtocol.formatSleepMinute(raw)))
        assertEquals("SM 1700000000000 U 7200 16 300 42 - - 0 -12 3 998 3 4 5 86 90 7", WearLinkProtocol.formatSleepMinute(unworn))
        assertEquals(unworn, WearLinkProtocol.parseSleepMinute(WearLinkProtocol.formatSleepMinute(unworn)))
        assertEquals(4.2f, raw.movement)
        assertEquals(1.5f, raw.heartRateSd)
        assertEquals(0.7f, raw.zAngleDelta)
    }

    @Test
    fun `a sleep minute with an implausible rate keeps the minute and drops the rate`() {
        val parsed = WearLinkProtocol.parseSleepMinute("SM 1700000000000 A 7200 20 300 100 300 15 6 0 0 1000 3 3 3 - - -")

        assertEquals(WearLinkProtocol.MinuteKind.AWAKE, parsed?.kind)
        assertNull(parsed?.bpm)
        assertNull(parsed?.zAngleMin)
        assertTrue(parsed!!.hasFlag(WearLinkProtocol.FLAG_SCREEN_ON))
    }

    @Test
    fun `malformed sleep minutes parse to null`() {
        assertNull(WearLinkProtocol.parseSleepMinute("SM 1700000000000 R 7200 16 300 42 58 15 6 -12 3 998 3 4 5 86 90"))
        assertNull(WearLinkProtocol.parseSleepMinute("SM 1700000000000 X 7200 16 300 42 58 15 6 -12 3 998 3 4 5 86 90 7"))
        assertNull(WearLinkProtocol.parseSleepMinute("SM 1700000000000 R 7200 16 300 -1 58 15 6 -12 3 998 3 4 5 86 90 7"))
        assertNull(WearLinkProtocol.parseSleepMinute("SM 1700000000000 R 99999 16 300 42 58 15 6 -12 3 998 3 4 5 86 90 7"))
        assertNull(WearLinkProtocol.parseSleepMinute("SM 1700000000000 R 7200 16 300 42 58 1.5 6 -12 3 998 3 4 5 86 90 7"))
        assertNull(WearLinkProtocol.parseSleepMinute("HR 1700000000000 58"))
    }

    @Test
    fun `a hello round-trips, with the name cleaned and clipped`() {
        val token = WearLinkToken.generate()
        val hello = WearLinkProtocol.Hello(4, setOf("sm", "hr"), token, "  Pixel\t6  Pro\u0007 ")

        val line = WearLinkProtocol.formatHello(hello)

        assertEquals("HELLO 4 hr,sm $token Pixel 6 Pro", line)
        assertEquals(hello.copy(capabilities = setOf("hr", "sm"), name = "Pixel 6 Pro"), WearLinkProtocol.parseHello(line))
        assertEquals(48, WearLinkProtocol.parseHello("HELLO 4 - $token " + "n".repeat(60))!!.name.length)
        assertEquals(emptySet<String>(), WearLinkProtocol.parseHello("HELLO 4 - $token")!!.capabilities)
    }

    @Test
    fun `a hello without a well-formed token or version is refused`() {
        val token = WearLinkToken.generate()

        assertNull(WearLinkProtocol.parseHello("HELLO 4 hr short Pixel"))
        assertNull(WearLinkProtocol.parseHello("HELLO x hr $token Pixel"))
        assertNull(WearLinkProtocol.parseHello("HELLO 4 hr,,sm $token Pixel"))
        assertNull(WearLinkProtocol.parseHello("PING"))
    }

    @Test
    fun `every hello reply round-trips`() {
        val replies = listOf(
            WearLinkProtocol.HelloReply.Ok(4, setOf("hr", "sm"), "Galaxy Watch8 (89FZ)"),
            WearLinkProtocol.HelloReply.Pending,
            WearLinkProtocol.HelloReply.Unauthorized(WearLinkProtocol.UnauthorizedReason.MISMATCH),
            WearLinkProtocol.HelloReply.Unauthorized(WearLinkProtocol.UnauthorizedReason.BLOCKED),
            WearLinkProtocol.HelloReply.VersionMismatch(4, 5),
            WearLinkProtocol.HelloReply.Error(WearLinkProtocol.ErrorCode.BUSY),
        )

        for (reply in replies) assertEquals(reply, WearLinkProtocol.parseHelloReply(WearLinkProtocol.formatHelloReply(reply)))
        assertEquals("OK 4 hr,sm Galaxy Watch8 (89FZ)", WearLinkProtocol.formatHelloReply(replies[0]))
        assertNull(WearLinkProtocol.parseHelloReply("UNAUTHORIZED why"))
        assertNull(WearLinkProtocol.parseHelloReply("VERSION 5 4"))
        assertNull(WearLinkProtocol.parseHelloReply("PENDING now"))
    }

    @Test
    fun `the watch's clock sits before the name only where the reply says tm`() {
        val ok = WearLinkProtocol.HelloReply.Ok(5, setOf("hr", "sm"), "Galaxy Watch8 (89FZ)", clockMillis = 1_763_800_000_000L)

        assertEquals("OK 5 hr,sm,tm 1763800000000 Galaxy Watch8 (89FZ)", WearLinkProtocol.formatHelloReply(ok))
        assertEquals(ok, WearLinkProtocol.parseHelloReply(WearLinkProtocol.formatHelloReply(ok)))
        // Without tm a number is the start of the name, as every older phone reads it.
        assertEquals(
            WearLinkProtocol.HelloReply.Ok(5, setOf("hr"), "1763800000000 Watch"),
            WearLinkProtocol.parseHelloReply("OK 5 hr 1763800000000 Watch"),
        )
        assertNull(WearLinkProtocol.parseHelloReply("OK 5 hr,tm Galaxy Watch8"))
    }

    @Test
    fun `requests parse to one type each`() {
        assertEquals(WearLinkProtocol.Request.Ping, WearLinkProtocol.parseRequest(" PING "))
        assertEquals(WearLinkProtocol.Request.SeriesSince("st", "t10", 2000), WearLinkProtocol.parseRequest("SINCE st t10 99999"))
        assertEquals(WearLinkProtocol.Request.LiveHeartRate(WearLinkProtocol.MAX_LIVE_SECONDS), WearLinkProtocol.parseRequest("HR_LIVE 600"))
        assertNull(WearLinkProtocol.parseRequest("HR_LIVE 0"))
        assertNull(WearLinkProtocol.parseRequest("HELLO 4 - x y"))
        val requests = listOf(
            WearLinkProtocol.Request.Ping,
            WearLinkProtocol.Request.WorkoutsSince(3, 4),
            WearLinkProtocol.Request.PhoneActive(5),
            WearLinkProtocol.Request.LiveHeartRate(5),
            WearLinkProtocol.Request.SeriesSince("rr", "0123456789abcdef.12", 7),
        )
        for (request in requests) {
            assertEquals(request, WearLinkProtocol.parseRequest(WearLinkProtocol.formatRequest(request)))
        }
    }

    @Test
    fun `a step minute round-trips, and one off the minute, empty or past a sprint is refused`() {
        val minute = WearLinkProtocol.StepMinute(1_700_000_040_000L, 112)

        assertEquals(minute, WearLinkProtocol.parseStepMinute(WearLinkProtocol.formatStepMinute(minute)))
        assertNull(WearLinkProtocol.parseStepMinute("ST 1700000041000 112"))
        assertNull(WearLinkProtocol.parseStepMinute("ST 1700000040000 0"))
        assertNull(WearLinkProtocol.parseStepMinute("ST 1700000040000 ${WearLinkProtocol.MAX_STEPS_PER_MINUTE + 1}"))
        assertNull(WearLinkProtocol.parseStepMinute("HR 1700000040000 112"))
    }

    @Test
    fun `an ascent minute round-trips, and one off the minute, empty or past an elevator is refused`() {
        val minute = WearLinkProtocol.AscentMinute(1_700_000_040_000L, 32)
        assertEquals("AS 1700000040000 32", WearLinkProtocol.formatAscentMinute(minute))
        assertEquals(minute, WearLinkProtocol.parseAscentMinute(WearLinkProtocol.formatAscentMinute(minute)))
        assertNull(WearLinkProtocol.parseAscentMinute("AS 1700000041000 32"))
        assertNull(WearLinkProtocol.parseAscentMinute("AS 1700000040000 0"))
        assertNull(WearLinkProtocol.parseAscentMinute("AS 1700000040000 ${WearLinkProtocol.MAX_ASCENT_DECIMETRES_PER_MINUTE + 1}"))
        // A line of the builds that sent whole floors is not ascent.
        assertNull(WearLinkProtocol.parseAscentMinute("FL 1700000040000 3"))
    }

    @Test
    fun `breathing and skin lines round-trip and refuse what no wrist gives`() {
        val rr = WearLinkProtocol.RespiratoryWindow(1_700_000_100_000L, 142, 2)
        assertEquals("RR 1700000100000 142 2", WearLinkProtocol.formatRespiratoryWindow(rr))
        assertEquals(rr, WearLinkProtocol.parseRespiratoryWindow(WearLinkProtocol.formatRespiratoryWindow(rr)))
        assertNull(WearLinkProtocol.parseRespiratoryWindow("RR 1700000100000 40 2"))
        assertNull(WearLinkProtocol.parseRespiratoryWindow("RR 1700000100000 142 3"))
        val sk = WearLinkProtocol.SkinMinute(1_700_000_040_000L, 3412, 2250)
        assertEquals(sk, WearLinkProtocol.parseSkinMinute(WearLinkProtocol.formatSkinMinute(sk)))
        assertNull(WearLinkProtocol.parseSkinMinute("SK 1700000040000 1500 2250"))
        assertNull(WearLinkProtocol.parseSkinMinute("SK 1700000040001 3412 2250"))
    }

    @Test
    fun `an SpO2 line round-trips with or without its heart rate and refuses a failed reading`() {
        val o2 = WearLinkProtocol.Spo2Measurement(1_700_000_300_000L, 96, 54)
        assertEquals("O2 1700000300000 96 54", WearLinkProtocol.formatSpo2(o2))
        assertEquals(o2, WearLinkProtocol.parseSpo2(WearLinkProtocol.formatSpo2(o2)))
        assertEquals(o2.copy(bpm = null), WearLinkProtocol.parseSpo2("O2 1700000300000 96 -"))
        assertNull(WearLinkProtocol.parseSpo2("O2 1700000300000 0 54"))
        assertNull(WearLinkProtocol.parseSpo2("O2 1700000300000 101 54"))
    }

    @Test
    fun `preference lines round-trip and refuse what is not a token`() {
        assertEquals(WearLinkProtocol.Request.GetPreferences, WearLinkProtocol.parseRequest("GET_PREF"))
        assertEquals(WearLinkProtocol.Request.SetPreference("hr_source", "samsung"), WearLinkProtocol.parseRequest("SET_PREF hr_source samsung"))
        assertNull(WearLinkProtocol.parseRequest("SET_PREF hr_source"))
        assertNull(WearLinkProtocol.parseRequest("SET_PREF hr-source samsung"))
        assertEquals("skin" to "1", WearLinkProtocol.parsePreference(WearLinkProtocol.formatPreference("skin", "1")))
        assertNull(WearLinkProtocol.parsePreference("PREF skin"))
    }

    @Test
    fun `phone active round-trips and refuses a bad time`() {
        assertEquals("PHONE_ACTIVE 1700000000000", WearLinkProtocol.formatRequest(WearLinkProtocol.Request.PhoneActive(1_700_000_000_000L)))
        assertEquals(WearLinkProtocol.Request.PhoneActive(1_700_000_000_000L), WearLinkProtocol.parseRequest("PHONE_ACTIVE 1700000000000"))
        assertNull(WearLinkProtocol.parseRequest("PHONE_ACTIVE 0"))
        assertNull(WearLinkProtocol.parseRequest("PHONE_ACTIVE x"))
        assertNull(WearLinkProtocol.parseRequest("PHONE_ACTIVE"))
    }

    @Test
    fun `an HRV window round-trips, and one with an impossible RMSSD or beat count is refused`() {
        val window = WearLinkProtocol.HrvWindow(1_700_000_100_000L, 452, 280)
        assertEquals("HV 1700000100000 452 280", WearLinkProtocol.formatHrvWindow(window))
        assertEquals(window, WearLinkProtocol.parseHrvWindow(WearLinkProtocol.formatHrvWindow(window)))
        assertEquals(45.2, window.rmssdMillis, 1e-9)
        assertNull(WearLinkProtocol.parseHrvWindow("HV 1700000100000 0 280"))
        assertNull(WearLinkProtocol.parseHrvWindow("HV 1700000100000 ${WearLinkProtocol.MAX_RMSSD10 + 1} 280"))
        assertNull(WearLinkProtocol.parseHrvWindow("HV 1700000100000 452 0"))
        assertNull(WearLinkProtocol.parseHrvWindow("HV 0 452 280"))
        assertNull(WearLinkProtocol.parseHrvWindow("HV 1700000100000 452"))
        assertNull(WearLinkProtocol.parseHrvWindow("AS 1700000040000 32"))
    }

    @Test
    fun `ping tolerates surrounding whitespace`() {
        assertTrue(WearLinkProtocol.isPing(" PING\r"))
    }

    @Test
    fun `a workout request round-trips and clamps its limit`() {
        assertEquals(WearLinkProtocol.Request.WorkoutsSince(10, WearLinkProtocol.MAX_WORKOUTS_PER_REQUEST), WearLinkProtocol.parseRequest("WO_SINCE 10 99999"))
        assertEquals("WO_SINCE 10 5", WearLinkProtocol.formatRequest(WearLinkProtocol.Request.WorkoutsSince(10, 99999)))
        assertNull(WearLinkProtocol.parseRequest("WO_SINCE 10"))
        assertNull(WearLinkProtocol.parseRequest("WO_SINCE -1 5"))
    }

    @Test
    fun `a data line that grew at its end still reads, its new fields ignored`() {
        val p = WearLinkProtocol
        fun grown(line: String) = "$line 42 future"

        assertEquals(WearLinkProtocol.HeartRateSample(1_700_000_000_000, 61), p.parseSample(grown(p.formatSample(1_700_000_000_000, 61))))
        assertEquals(WearLinkProtocol.End(3, true), p.parseEnd(grown(p.formatEnd(3, true))))
        val step = WearLinkProtocol.StepMinute(1_700_000_040_000, 12)
        assertEquals(step, p.parseStepMinute(grown(p.formatStepMinute(step))))
        val ascent = WearLinkProtocol.AscentMinute(1_700_000_040_000, 31)
        assertEquals(ascent, p.parseAscentMinute(grown(p.formatAscentMinute(ascent))))
        val raw = p.parseSleepMinute("SM 1700000000000 R 7200 16 300 42 58 15 6 -12 3 998 3 4 5 86 90 7")!!
        assertEquals(raw, p.parseSleepMinute(grown(p.formatSleepMinute(raw))))
    }
}
