package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.devices.wearos.WearOsSkinTemperatureImport.NightMedian

class WearOsSkinTemperatureImportTest {

    private val t0 = Instant.parse("2026-11-08T22:00:00Z")

    private fun minutes(from: Int, count: Int, celsius: (Int) -> Double = { 34.0 }) =
        List(count) { WearOsSkinMinute(t0.plusSeconds(60L * (from + it)), celsius(from + it)) }

    @Test
    fun `a gap over half an hour starts a new run`() {
        val runs = WearOsSkinTemperatureImport.runs(minutes(0, 60) + minutes(100, 60))
        assertEquals(2, runs.size)
        assertEquals(t0, runs[0].start)
        assertEquals(t0.plusSeconds(6000), runs[1].start)
    }

    @Test
    fun `only runs that ended half an hour ago and lasted half an hour count`() {
        val runs = WearOsSkinTemperatureImport.runs(minutes(0, 20) + minutes(60, 120))
        val now = t0.plusSeconds(60L * (179 + 30))
        assertTrue(WearOsSkinTemperatureImport.finished(runs, now).map { it.start } == listOf(t0.plusSeconds(3600)))
        assertTrue(WearOsSkinTemperatureImport.finished(runs, t0.plusSeconds(60L * 185)).isEmpty())
    }

    private fun span(fromMinute: Long, toMinute: Long) = t0.plusSeconds(60 * fromMinute)..t0.plusSeconds(60 * toMinute)

    @Test
    fun `a night's runs keep only the minutes inside a sleep session`() {
        // 22:00 to 02:00 recorded from bedtime mode; asleep 23:40 to 01:30.
        val runs = WearOsSkinTemperatureImport.runs(minutes(0, 240))
        val asleep = WearOsSkinTemperatureImport.clipToSleep(runs, listOf(span(100, 210)))
        assertEquals(t0.plusSeconds(60 * 100), asleep.single().start)
        assertEquals(t0.plusSeconds(60 * 209), asleep.single().end)
        assertEquals(110, asleep.single().minutes.size)
    }

    @Test
    fun `a run outside every session of its night is dropped, one inside two sessions keeps both parts`() {
        val runs = WearOsSkinTemperatureImport.runs(minutes(0, 60) + minutes(120, 180))
        val asleep = WearOsSkinTemperatureImport.clipToSleep(runs, listOf(span(150, 200), span(220, 260)))
        assertEquals(1, asleep.size)
        assertEquals(50 + 40, asleep.single().minutes.size)
    }

    @Test
    fun `a run cut to a few minutes is no night, and a cut run keeps its record id`() {
        val run = WearOsSkinTemperatureImport.runs(minutes(0, 190)).single()
        // Taken off at 01:10, asleep from 01:00 by another app: ten minutes are not a night.
        assertTrue(WearOsSkinTemperatureImport.clipToSleep(listOf(run), listOf(span(180, 600))).isEmpty())
        val cut = WearOsSkinTemperatureImport.clipToSleep(listOf(run), listOf(span(60, 600))).single()
        assertEquals(t0.plusSeconds(60 * 60), cut.start)
        // A re-pull that cuts elsewhere still writes over the same record.
        assertEquals(WearOsSkinTemperatureImport.clientRecordId(run), WearOsSkinTemperatureImport.clientRecordId(cut))
    }

    private val utc = ZoneOffset.UTC

    @Test
    fun `a night with a session anywhere in it is cut to it, whichever runs this sync brought`() {
        // Bedtime 22:00 to 23:30, then the charger; another app's session 00:30 to 07:00.
        val evening = WearOsSkinTemperatureImport.runs(minutes(0, 90))
        val night = LocalDate.parse("2026-11-08")
        assertEquals(
            emptyList<WearOsSkinTemperatureImport.Run>(),
            WearOsSkinTemperatureImport.asleepRuns(night, evening, listOf(span(150, 540)), utc, now = t0.plusSeconds(3600L * 10)),
        )
    }

    @Test
    fun `a night without a session yet waits, a night over without one stands as it is`() {
        val evening = WearOsSkinTemperatureImport.runs(minutes(0, 90))
        val night = LocalDate.parse("2026-11-08")
        // 23:30 that night: the session may still come.
        assertNull(WearOsSkinTemperatureImport.asleepRuns(night, evening, emptyList(), utc, now = t0.plusSeconds(60L * 120)))
        // Past noon the next day: nothing will come, so the runs stand, as before sessions were read.
        assertEquals(evening, WearOsSkinTemperatureImport.asleepRuns(night, evening, listOf(span(-2000, -1900)), utc, now = t0.plusSeconds(3600L * 15)))
        // Without sessions to read at all: as before.
        assertEquals(evening, WearOsSkinTemperatureImport.asleepRuns(night, evening, null, utc, now = t0.plusSeconds(60L * 120)))
    }

    @Test
    fun `a session's awake stages are not asleep`() {
        val session = SleepSessionRecord(
            startTime = t0, startZoneOffset = utc, endTime = t0.plusSeconds(60L * 540), endZoneOffset = utc,
            stages = listOf(
                SleepSessionRecord.Stage(t0, t0.plusSeconds(60L * 100), SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED),
                SleepSessionRecord.Stage(t0.plusSeconds(60L * 100), t0.plusSeconds(60L * 300), SleepSessionRecord.STAGE_TYPE_LIGHT),
                SleepSessionRecord.Stage(t0.plusSeconds(60L * 300), t0.plusSeconds(60L * 310), SleepSessionRecord.STAGE_TYPE_AWAKE),
                SleepSessionRecord.Stage(t0.plusSeconds(60L * 310), t0.plusSeconds(60L * 540), SleepSessionRecord.STAGE_TYPE_DEEP),
            ),
            metadata = Metadata.manualEntry(),
        )
        assertEquals(listOf(span(100, 300), span(310, 540)), WearOsSkinTemperatureImport.asleepSpans(session))
        val plain = SleepSessionRecord(t0, utc, t0.plusSeconds(60L * 540), utc, metadata = Metadata.manualEntry())
        assertEquals(listOf(span(0, 540)), WearOsSkinTemperatureImport.asleepSpans(plain))
    }

    private fun night(date: String, celsius: Double, minutes: Int = 60) = NightMedian(LocalDate.parse(date), celsius, minutes)

    @Test
    fun `the baseline is the other nights' median once there are three, else unknown`() {
        val tonight = LocalDate.parse("2026-11-08")
        assertNull(WearOsSkinTemperatureImport.baseline(tonight, emptyList()))
        assertNull(WearOsSkinTemperatureImport.baseline(tonight, listOf(night("2026-11-05", 33.0), night("2026-11-06", 33.5))))
        val three = listOf(night("2026-11-05", 33.0), night("2026-11-06", 33.5), night("2026-11-07", 34.0))
        assertEquals(33.5, WearOsSkinTemperatureImport.baseline(tonight, three)!!, 1e-9)
        // A median remembered before nights had dates still counts as another night.
        val legacy = listOf(NightMedian(null, 33.0), night("2026-11-06", 33.5), night("2026-11-07", 34.0))
        assertEquals(33.5, WearOsSkinTemperatureImport.baseline(tonight, legacy)!!, 1e-9)
    }

    @Test
    fun `a night is never part of its own baseline`() {
        val sameNight = listOf(night("2026-11-08", 34.0), night("2026-11-06", 33.5), night("2026-11-07", 34.0))
        assertNull(WearOsSkinTemperatureImport.baseline(LocalDate.parse("2026-11-08"), sameNight))
    }

    @Test
    fun `the runs of one night either side of midnight, and a nap that afternoon, share one night`() {
        val zone = ZoneOffset.ofHours(1)
        val runs = WearOsSkinTemperatureImport.runs(
            // 15:00 nap, 23:00 to 00:00, then 03:00 to 04:00 local.
            minutes(-480, 40) + minutes(0, 60) + minutes(240, 60),
        )
        assertEquals(3, runs.size)
        assertEquals(setOf(LocalDate.parse("2026-11-08")), runs.mapTo(HashSet()) { WearOsSkinTemperatureImport.nightOf(it, zone) })
    }

    @Test
    fun `a night joins the baseline once, with the median of all its runs`() {
        val tonight = LocalDate.parse("2026-11-08")
        val runs = WearOsSkinTemperatureImport.runs(minutes(0, 60) { 34.0 } + minutes(100, 60) { 35.0 } + minutes(200, 61) { 35.0 })
        assertEquals(listOf(night("2026-11-08", 35.0, minutes = 181)), WearOsSkinTemperatureImport.remember(emptyList(), tonight, runs))
    }

    @Test
    fun `a night that arrives in two syncs is one night, its median weighted by each part's minutes`() {
        val tonight = LocalDate.parse("2026-11-08")
        val before = listOf(night("2026-11-07", 33.0, minutes = 400), night("2026-11-08", 34.0, minutes = 60))
        val later = WearOsSkinTemperatureImport.runs(minutes(100, 180) { 35.0 })

        val after = WearOsSkinTemperatureImport.remember(before, tonight, later)

        // Still two nights; the 8th is now 60 minutes at 34.0 and 180 at 35.0.
        assertEquals(listOf(before[0], night("2026-11-08", 34.75, minutes = 240)), after)
        // Runs of which none was kept change nothing.
        assertEquals(before, WearOsSkinTemperatureImport.remember(before, tonight, emptyList()))
    }

    @Test
    fun `a night remembered without its minutes counts as much as the part that arrives`() {
        val tonight = LocalDate.parse("2026-11-08")
        val before = listOf(NightMedian(null, 33.0), night("2026-11-08", 34.0, minutes = 0))

        val second = WearOsSkinTemperatureImport.remember(before, tonight, WearOsSkinTemperatureImport.runs(minutes(100, 180) { 35.0 }))

        // The plain mean of the two medians, and from here on the night has minutes to weigh by.
        assertEquals(listOf(before[0], night("2026-11-08", 34.5, minutes = 360)), second)
        val third = WearOsSkinTemperatureImport.remember(second, tonight, WearOsSkinTemperatureImport.runs(minutes(400, 120) { 36.0 }))
        assertEquals(listOf(before[0], night("2026-11-08", 34.875, minutes = 480)), third)
    }

    @Test
    fun `the baseline remembers the newest thirty nights, and a night it knows takes no other's place`() {
        val tonight = LocalDate.parse("2026-11-08")
        val month = List(30) { night(LocalDate.parse("2026-10-01").plusDays(it.toLong()).toString(), 34.0) }
        val run = WearOsSkinTemperatureImport.runs(minutes(0, 60))

        val remembered = WearOsSkinTemperatureImport.remember(month, tonight, run)

        assertEquals(30, remembered.size)
        assertEquals(LocalDate.parse("2026-10-02"), remembered.first().night)
        assertEquals(tonight, remembered.last().night)

        // More of a night in the middle of the thirty: every night stays, each where it was.
        val more = WearOsSkinTemperatureImport.remember(remembered, LocalDate.parse("2026-10-15"), run)
        assertEquals(remembered.map { it.night }, more.map { it.night })
        assertEquals(remembered.map { if (it.night == LocalDate.parse("2026-10-15")) 120 else it.minutes }, more.map { it.minutes })
    }

    @Test
    fun `the nights the baseline still lacks fall to zero at three, and a night without a date counts`() {
        val nights = listOf(NightMedian(null, 33.0), night("2026-11-06", 33.5), night("2026-11-07", 34.0), night("2026-11-08", 34.0))
        assertEquals(listOf(3, 2, 1, 0, 0), (0..4).map { WearOsSkinTemperatureImport.nightsToBaseline(nights.take(it)) })
        // What the count promises: with none to go, the next night has a baseline.
        assertEquals(33.5, WearOsSkinTemperatureImport.baseline(LocalDate.parse("2026-11-08"), nights.take(3))!!, 1e-9)
    }

    @Test
    fun `the record holds one delta per minute against the baseline, at the wrist`() {
        val run = WearOsSkinTemperatureImport.runs(minutes(0, 60) { 34.0 + it / 100.0 }).single()
        val record = WearOsSkinTemperatureImport.record(run, 34.0, ZoneOffset.ofHours(1), version = 5)!!
        assertEquals(60, record.deltas.size)
        assertEquals(0.59, record.deltas.last().delta.inCelsius, 1e-9)
        assertEquals(34.0, record.baseline!!.inCelsius, 1e-9)
        assertEquals("wearos_skin_${t0.toEpochMilli()}", record.metadata.clientRecordId)
        assertEquals(t0.plusSeconds(60L * 60), record.endTime)
    }
}
