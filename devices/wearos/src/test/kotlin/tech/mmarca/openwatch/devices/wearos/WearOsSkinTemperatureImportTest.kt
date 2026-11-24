package tech.mmarca.openwatch.devices.wearos

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

    private fun night(date: String, celsius: Double) = WearOsSkinTemperatureImport.NightMedian(LocalDate.parse(date), celsius)

    @Test
    fun `the baseline is the other nights' median once there are three, else unknown`() {
        val tonight = LocalDate.parse("2026-11-08")
        assertNull(WearOsSkinTemperatureImport.baseline(tonight, emptyList()))
        assertNull(WearOsSkinTemperatureImport.baseline(tonight, listOf(night("2026-11-05", 33.0), night("2026-11-06", 33.5))))
        val three = listOf(night("2026-11-05", 33.0), night("2026-11-06", 33.5), night("2026-11-07", 34.0))
        assertEquals(33.5, WearOsSkinTemperatureImport.baseline(tonight, three)!!, 1e-9)
        // A median remembered before nights had dates still counts as another night.
        val legacy = listOf(WearOsSkinTemperatureImport.NightMedian(null, 33.0), night("2026-11-06", 33.5), night("2026-11-07", 34.0))
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
        val remembered = WearOsSkinTemperatureImport.remember(emptyList(), tonight, runs)
        assertEquals(listOf(night("2026-11-08", 35.0)), remembered)
        // A run of the same night from a later sync leaves the night's median as it was.
        val later = WearOsSkinTemperatureImport.runs(minutes(400, 60) { 30.0 })
        assertEquals(remembered, WearOsSkinTemperatureImport.remember(remembered, tonight, later))
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
