package tech.mmarca.openwatch.wear

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowPowerManager
import tech.mmarca.openwatch.wearlink.WearLinkEntry
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

/** The spot measurements over sources that hand their readings at once, and a real store. */
@RunWith(AndroidJUnit4::class)
class SpotMeasurementsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = MetricStore(app, name = null)
    private val entries = EntryLog(store)
    private var granted = true

    @After
    fun close() = store.close()

    /** A source that hands [readings] as soon as it is listened to, and counts its open handles. */
    private class FakeSensor<T>(private val readings: List<T>, private val refuses: Boolean = false) : SpotSensor<T> {
        var listened = 0
        var open = 0

        override fun listen(onReading: (T) -> Unit): AutoCloseable? {
            listened++
            if (refuses) return null
            open++
            readings.forEach(onReading)
            return AutoCloseable { open-- }
        }
    }

    private val claim = SensorClaim()
    private val spo2Claim = SensorClaim()

    /** The platform's SpO2 tracker: hands [batches] once started, on its handler, and counts its starts. */
    private class FakeSpo2(private val batches: List<List<Spo2Reading>>) : SpotSource<Spo2Reading> {
        var started = 0

        override val isAvailable: Boolean = true

        override fun start(handler: android.os.Handler, sink: SpotSource.Sink<Spo2Reading>): Boolean {
            started++
            batches.forEach(sink::onReadings)
            return true
        }

        override fun stop() = Unit
    }

    private fun spot(
        heartRate: SpotSensor<LiveHeartRate.Reading>? = null,
        heartBeats: SpotSensor<Double>? = null,
        motion: SpotSensor<Motion>? = null,
        pressure: SpotSensor<Float>? = null,
        spo2: SpotSource<Spo2Reading>? = null,
    ) = SpotMeasurements(
        app,
        heartRate = { heartRate },
        hrvWindows = { heartBeats?.let(::HeartBeatWindowSource) },
        motion = { motion },
        hrvClaim = claim,
        pressure = { pressure },
        spo2 = { spo2 },
        spo2Claim = spo2Claim,
        spo2Granted = { granted },
        heartRateGranted = { granted },
        entries = entries,
        debug = DebugProtocol.off(),
        // Long enough for every fake's readings; short where a measurement waits its time out.
        limitMillis = { 300L },
    )

    private fun events(spot: SpotMeasurements, kind: SpotKind): List<SpotEvent> =
        runBlocking { withTimeout(TIMEOUT_MILLIS) { spot.run(kind).toList() } }

    private fun stored(): List<WearLinkEntry> = store.since(WearMetrics.ENTRIES, 0, 100)

    private fun sample(bpm: Int): LiveHeartRate.Reading = LiveHeartRate.Reading.Sample(WearLinkProtocol.HeartRateSample(System.currentTimeMillis(), bpm))

    @Test
    fun `a heart rate settles, is shown as it comes and is kept as a measured entry`() {
        val sensor = FakeSensor(
            listOf(LiveHeartRate.Reading.NoContact, LiveHeartRate.Reading.Unreliable) + List(SpotRules.HEART_RATE_READINGS) { sample(64) },
        )

        val events = events(spot(heartRate = sensor), SpotKind.HEART_RATE)

        assertEquals(SpotEvent.Waiting(offBody = true), events[0])
        assertEquals(SpotEvent.Waiting(offBody = false), events[1])
        assertEquals(List(SpotRules.HEART_RATE_READINGS) { SpotEvent.Live(64.0) }, events.subList(2, events.size - 1))
        assertEquals(SpotEvent.Result(64.0), events.last())
        assertEquals(0, sensor.open)
        val entry = stored().single()
        assertEquals(WearLinkEntry.Type.HEART_RATE, entry.type)
        assertEquals(64.0, entry.value, 0.0)
        assertTrue(entry.measured)
    }

    @Test
    fun `a watch off the wrist the whole time says so, and keeps nothing`() {
        val events = events(spot(heartRate = FakeSensor(listOf(LiveHeartRate.Reading.NoContact))), SpotKind.HEART_RATE)

        assertEquals(SpotEvent.Failed(SpotFailure.OFF_BODY), events.last())
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `too few readings in the time is no signal`() {
        val events = events(spot(heartRate = FakeSensor(List(3) { sample(70) })), SpotKind.HEART_RATE)

        assertEquals(SpotEvent.Failed(SpotFailure.NO_SIGNAL), events.last())
    }

    @Test
    fun `without the grant, without the sensor or with a refused registration nothing is measured`() {
        val sensor = FakeSensor(List(SpotRules.HEART_RATE_READINGS) { sample(64) })
        granted = false
        assertEquals(listOf(SpotEvent.Failed(SpotFailure.PERMISSION_DENIED)), events(spot(heartRate = sensor), SpotKind.HEART_RATE))
        assertEquals(0, sensor.listened)

        granted = true
        assertEquals(listOf(SpotEvent.Failed(SpotFailure.NOT_SUPPORTED)), events(spot(), SpotKind.HRV))
        assertEquals(
            listOf(SpotEvent.Failed(SpotFailure.FAILED)),
            events(spot(pressure = FakeSensor(emptyList(), refuses = true)), SpotKind.AIR_PRESSURE),
        )
        assertTrue(stored().isEmpty())
    }

    /** Alternating intervals of 800 and 840 ms: an RMSSD of 40 ms. */
    private val beats = (0..SpotRules.HRV_MIN_CLEAN_BEATS + 1).runningFold(10_000.0) { at, index -> at + if (index % 2 == 0) 800.0 else 840.0 }

    /** Ten accelerometer readings a second for twenty seconds, [swing] m/s² either side of gravity. */
    private fun arm(swing: Double) = List(200) { Motion(timeMillis = it * 100L, magnitude = 9.81 + if (it % 2 == 0) swing else -swing) }

    @Test
    fun `an HRV is the RMSSD of its window, the pulse shown as it runs, with the heart rate sensor held for it`() {
        val beatSensor = FakeSensor(beats)
        val pulse = FakeSensor(List(3) { sample(72) })

        val events = events(spot(heartRate = pulse, heartBeats = beatSensor, motion = FakeSensor(arm(0.01))), SpotKind.HRV)

        assertEquals(List(3) { SpotEvent.Live(72.0) }, events.dropLast(1))
        assertEquals(40.0, (events.last() as SpotEvent.Result).value, 1e-9)
        assertEquals(1, pulse.listened)
        assertEquals(0, pulse.open)
        assertEquals(0, beatSensor.open)
        val entry = stored().single()
        assertEquals(WearLinkEntry.Type.HRV, entry.type)
        assertEquals(40.0, entry.value, 1e-3)
        assertTrue(entry.measured)
        assertEquals(null, claim.holder)
    }

    @Test
    fun `an HRV from an arm that moved is not kept`() {
        val events = events(spot(heartBeats = FakeSensor(beats), motion = FakeSensor(arm(2.0))), SpotKind.HRV)

        assertEquals(SpotEvent.Failed(SpotFailure.MOVED), events.last())
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `a spot HRV waits while the night's HRV window holds the sensor`() {
        val beatSensor = FakeSensor(beats)
        assertTrue(claim.take("night"))

        assertEquals(listOf(SpotEvent.Failed(SpotFailure.BUSY)), events(spot(heartBeats = beatSensor), SpotKind.HRV))
        assertEquals(0, beatSensor.listened)

        claim.release("night")
        assertTrue(events(spot(heartBeats = beatSensor), SpotKind.HRV).last() is SpotEvent.Result)
    }

    @Test
    fun `the air pressure is shown with its altitude, and not kept`() {
        val events = events(spot(pressure = FakeSensor(listOf(1000f, 1001f, 1002f, 1003f, 1004f, 1100f))), SpotKind.AIR_PRESSURE)

        val result = events.last() as SpotEvent.Result
        assertEquals(SpotRules.PRESSURE_READINGS, events.count { it is SpotEvent.Live })
        assertEquals(1002.0, result.value, 1e-9)
        assertEquals(AscentMinuteCounter.altitudeMetres(1002.0), result.secondary!!, 1e-9)
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `a blood oxygen is the tracker's first completed reading since the start, kept as a measured entry`() {
        val now = System.currentTimeMillis()
        val tracker = FakeSpo2(
            listOf(
                // The platform's last reading, from before the start: not this one.
                listOf(Spo2Reading(now - 60_000, percent = 91, bpm = 70, moving = false)),
                listOf(Spo2Reading(now, percent = null, bpm = 70, moving = false)),
                listOf(Spo2Reading(now + 13_000, percent = 97, bpm = 68, moving = false)),
            ),
        )

        val events = events(spot(spo2 = tracker), SpotKind.SPO2)

        assertEquals(SpotEvent.Result(97.0), events.last())
        assertEquals(1, tracker.started)
        val entry = stored().single()
        assertEquals(WearLinkEntry.Type.BLOOD_OXYGEN, entry.type)
        assertEquals(97.0, entry.value, 0.0)
        assertTrue(entry.measured)
        assertEquals(null, spo2Claim.holder)
    }

    @Test
    fun `a blood oxygen from a moving arm, without the grant or while the night's runs, is none`() {
        val moving = FakeSpo2(listOf(listOf(Spo2Reading(System.currentTimeMillis(), percent = null, bpm = 0, moving = true))))
        assertEquals(SpotEvent.Failed(SpotFailure.MOVED), events(spot(spo2 = moving), SpotKind.SPO2).last())

        assertTrue(spo2Claim.take("night"))
        val waiting = FakeSpo2(emptyList())
        assertEquals(listOf(SpotEvent.Failed(SpotFailure.BUSY)), events(spot(spo2 = waiting), SpotKind.SPO2))
        assertEquals(0, waiting.started)
        spo2Claim.release("night")

        granted = false
        assertEquals(listOf(SpotEvent.Failed(SpotFailure.PERMISSION_DENIED)), events(spot(spo2 = waiting), SpotKind.SPO2))
        assertEquals(listOf(SpotEvent.Failed(SpotFailure.NOT_SUPPORTED)), events(spot(), SpotKind.SPO2))
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `a measurement holds a wake lock of its own while it runs, and lets it go`() {
        val sensor = FakeSensor(List(SpotRules.HEART_RATE_READINGS) { sample(64) })

        events(spot(heartRate = sensor), SpotKind.HEART_RATE)

        val lock = ShadowPowerManager.getLatestWakeLock()
        assertEquals("OpenWatch:spot", shadowOf(lock).tag)
        assertFalse(lock.isHeld)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
