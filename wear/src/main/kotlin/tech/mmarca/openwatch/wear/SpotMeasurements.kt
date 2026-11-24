package tech.mmarca.openwatch.wear

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import tech.mmarca.openwatch.wearlink.WearLinkEntry

/**
 * The spot measurements the wearer starts by hand ([SpotKind]), built once
 * in [WearGraph] over the sources it hands in, so no screen opens a sensor:
 * the heart rate through [LiveHeartRate.listen], for the HRV a window of its
 * own ([HrvWindowSource]: the single beats of a heart-beat sensor, the raw
 * pulse signal, or the Samsung platform's intervals) with the accelerometer
 * beside it, the barometer once a second for the air pressure, and the
 * Samsung platform's SpO2 tracker for the blood oxygen, a source of its own
 * beside the night's. Each source is asked for when a measurement starts, off
 * the main thread, and is null where the watch lacks its sensor.
 *
 * [run] holds a partial wake lock of its own for as long as a measurement
 * lasts, so it ends with its reading even when the screen goes off. A heart
 * rate, an HRV and a blood oxygen are logged as entries measured on the watch
 * ([EntryLog], flag `m`); the air pressure is shown, not kept.
 */
internal class SpotMeasurements(
    context: Context,
    private val heartRate: () -> SpotSensor<LiveHeartRate.Reading>?,
    /** A window of the HRV's beats on this watch, chosen when it starts; null without a source. */
    private val hrvWindows: () -> HrvWindowSource?,
    /** The accelerometer, by which an HRV is kept only from a still arm; null without one. */
    private val motion: () -> SpotSensor<Motion>?,
    /** Shared with the night's HRV windows: one of the two at a time. */
    private val hrvClaim: SensorClaim,
    private val pressure: () -> SpotSensor<Float>?,
    /** The SpO2 tracker of the Samsung platform, its spot's own instance; null on a watch without it. */
    private val spo2: () -> SpotSource<Spo2Reading>? = { null },
    /** Shared with the night's SpO2 measurements: the platform starts its tracker for the first reader only. */
    private val spo2Claim: SensorClaim = SensorClaim(),
    private val spo2Granted: () -> Boolean = { false },
    private val heartRateGranted: () -> Boolean,
    private val entries: EntryLog,
    private val debug: DebugProtocol,
    /** How long a measurement of a kind may take, milliseconds; its own limit but in a test. */
    private val limitMillis: (SpotKind) -> Long = { it.seconds * MILLIS_PER_SECOND },
    /** How long an HRV window's source may take to hand its result over once the window ends. */
    private val resultWaitMillis: Long = RESULT_WAIT_MILLIS,
) {
    private val power = context.getSystemService(PowerManager::class.java)

    /**
     * Runs one measurement of [kind]: [SpotEvent.Live] and [SpotEvent.Waiting]
     * while it runs, then one [SpotEvent.Result] or [SpotEvent.Failed], and the
     * flow ends. Cancelling the collection stops it and lets go of the sensor.
     */
    fun run(kind: SpotKind): Flow<SpotEvent> = channelFlow {
        val lock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire(limitMillis(kind) + resultWaitMillis + WAKE_LOCK_MARGIN_MILLIS)
        }
        debug.event(TAG, "start", "kind" to kind.name)
        try {
            val end = when (kind) {
                SpotKind.HEART_RATE -> heartRate(kind)
                SpotKind.HRV -> hrv(kind)
                SpotKind.AIR_PRESSURE -> airPressure(kind)
                SpotKind.SPO2 -> spo2(kind)
            }
            if (end is SpotEvent.Result) keep(kind, end)
            debug.event(TAG, "end", "kind" to kind.name, "outcome" to ((end as? SpotEvent.Failed)?.failure?.name ?: "result"))
            send(end)
        } finally {
            if (lock?.isHeld == true) lock.release()
        }
    }.flowOn(Dispatchers.IO)

    /** The pulse once [SpotRules.settledHeartRate] settles it, within the kind's time. */
    private suspend fun ProducerScope<SpotEvent>.heartRate(kind: SpotKind): SpotEvent {
        val sensor = heartRate() ?: return SpotEvent.Failed(SpotFailure.NOT_SUPPORTED)
        if (!heartRateGranted()) return SpotEvent.Failed(SpotFailure.PERMISSION_DENIED)
        val readings = Channel<LiveHeartRate.Reading>(Channel.UNLIMITED)
        val handle = sensor.listen { readings.trySend(it) } ?: return SpotEvent.Failed(SpotFailure.FAILED)
        try {
            val bpms = ArrayList<Int>()
            var offBody = false
            val settled = withTimeoutOrNull(limitMillis(kind)) {
                for (reading in readings) {
                    when (reading) {
                        is LiveHeartRate.Reading.Sample -> {
                            offBody = false
                            bpms += reading.sample.bpm
                            send(SpotEvent.Live(reading.sample.bpm.toDouble()))
                        }
                        LiveHeartRate.Reading.NoContact -> {
                            offBody = true
                            send(SpotEvent.Waiting(offBody = true))
                        }
                        LiveHeartRate.Reading.Unreliable -> send(SpotEvent.Waiting(offBody = false))
                    }
                    SpotRules.settledHeartRate(bpms)?.let { return@withTimeoutOrNull it }
                }
                null
            }
            return settled?.let { SpotEvent.Result(it) }
                ?: SpotEvent.Failed(if (offBody) SpotFailure.OFF_BODY else SpotFailure.NO_SIGNAL)
        } finally {
            handle.close()
        }
    }

    /**
     * The RMSSD of the kind's whole window of beats from [hrvWindows], kept only from a still
     * arm ([Stillness]). The heart rate sensor stays registered for the window: it keeps the LEDs
     * lit for the raw pulse signal, and its pulse is the live value. A night's HRV window that
     * holds the sensor ([hrvClaim]) comes first; the spot then says the watch is busy.
     */
    private suspend fun ProducerScope<SpotEvent>.hrv(kind: SpotKind): SpotEvent {
        val source = hrvWindows() ?: return SpotEvent.Failed(SpotFailure.NOT_SUPPORTED)
        if (!heartRateGranted()) return SpotEvent.Failed(SpotFailure.PERMISSION_DENIED)
        if (!hrvClaim.take(CLAIM)) return SpotEvent.Failed(SpotFailure.BUSY)
        val worker = HandlerThread("SpotHrv").apply { start() }
        val handler = Handler(worker.looper)
        val pulses = Channel<LiveHeartRate.Reading>(Channel.UNLIMITED)
        val pulse = heartRate()?.listen { pulses.trySend(it) }
        val moves = MotionTrace()
        val stillness = motion()?.listen(moves::add)
        try {
            if (!onHandler(handler) { source.begin(handler, System.currentTimeMillis()) }) return SpotEvent.Failed(SpotFailure.FAILED)
            var offBody = false
            withTimeoutOrNull(limitMillis(kind)) {
                for (reading in pulses) {
                    offBody = reading == LiveHeartRate.Reading.NoContact
                    send(
                        when (reading) {
                            is LiveHeartRate.Reading.Sample -> SpotEvent.Live(reading.sample.bpm.toDouble())
                            LiveHeartRate.Reading.NoContact -> SpotEvent.Waiting(offBody = true)
                            LiveHeartRate.Reading.Unreliable -> SpotEvent.Waiting(offBody = false)
                        },
                    )
                }
            }
            val end = System.currentTimeMillis()
            val window = withTimeoutOrNull(resultWaitMillis) {
                suspendCancellableCoroutine { done -> handler.post { source.finish(end) { if (done.isActive) done.resume(it) } } }
            }
            val hrv = window?.hrv
            return when {
                stillness != null && moves.moved() -> SpotEvent.Failed(SpotFailure.MOVED)
                hrv == null || hrv.beatCount < SpotRules.HRV_MIN_CLEAN_BEATS -> SpotEvent.Failed(if (offBody) SpotFailure.OFF_BODY else SpotFailure.NO_SIGNAL)
                else -> SpotEvent.Result(hrv.rmssdMillis)
            }
        } finally {
            pulse?.close()
            stillness?.close()
            handler.post { source.cancel() }
            worker.quitSafely()
            hrvClaim.release(CLAIM)
        }
    }

    /** [block]'s answer, run on [handler]'s thread, where a window's source takes its calls. */
    private suspend fun <T> onHandler(handler: Handler, block: () -> T): T =
        suspendCancellableCoroutine { done -> handler.post { if (done.isActive) done.resume(block()) } }

    /** The accelerometer's readings through one window, from its sensor thread, judged once at the end. */
    private class MotionTrace {
        private val times = ArrayList<Long>()
        private val magnitudes = ArrayList<Double>()

        @Synchronized
        fun add(motion: Motion) {
            if (times.size >= MAX_MOTION_READINGS) return
            times += motion.timeMillis
            magnitudes += motion.magnitude
        }

        @Synchronized
        fun moved(): Boolean = Stillness.moved(times.toLongArray(), magnitudes.toDoubleArray())
    }

    /** The mean of the first [SpotRules.PRESSURE_READINGS] readings, with the altitude they stand for. */
    private suspend fun ProducerScope<SpotEvent>.airPressure(kind: SpotKind): SpotEvent {
        val sensor = pressure() ?: return SpotEvent.Failed(SpotFailure.NOT_SUPPORTED)
        val readings = Channel<Float>(Channel.UNLIMITED)
        val handle = sensor.listen { readings.trySend(it) } ?: return SpotEvent.Failed(SpotFailure.FAILED)
        try {
            val hectopascals = ArrayList<Float>()
            withTimeoutOrNull(limitMillis(kind)) {
                for (reading in readings) {
                    hectopascals += reading
                    send(SpotEvent.Live(reading.toDouble()))
                    if (hectopascals.size >= SpotRules.PRESSURE_READINGS) break
                }
            }
            val air = SpotRules.airPressure(hectopascals) ?: return SpotEvent.Failed(SpotFailure.NO_SIGNAL)
            return SpotEvent.Result(air.hectopascals, secondary = air.altitudeMetres)
        } finally {
            handle.close()
        }
    }

    /**
     * The first completed reading of the platform's SpO2 tracker since the start, within the
     * kind's time; a moving arm with no reading is [SpotFailure.MOVED]. A night's measurement that
     * holds the tracker ([spo2Claim]) comes first; the spot then says the watch is busy.
     */
    private suspend fun ProducerScope<SpotEvent>.spo2(kind: SpotKind): SpotEvent {
        val source = spo2() ?: return SpotEvent.Failed(SpotFailure.NOT_SUPPORTED)
        if (!spo2Granted()) return SpotEvent.Failed(SpotFailure.PERMISSION_DENIED)
        if (!spo2Claim.take(CLAIM)) return SpotEvent.Failed(SpotFailure.BUSY)
        val worker = HandlerThread("SpotSpo2").apply { start() }
        val handler = Handler(worker.looper)
        val batches = Channel<List<Spo2Reading>>(Channel.UNLIMITED)
        val errored = AtomicBoolean(false)
        val sink = object : SpotSource.Sink<Spo2Reading> {
            override fun onReadings(readings: List<Spo2Reading>) {
                batches.trySend(readings)
            }

            override fun onError() {
                errored.set(true)
                batches.close()
            }
        }
        val startedAt = System.currentTimeMillis()
        try {
            if (!onHandler(handler) { source.start(handler, sink) }) return SpotEvent.Failed(SpotFailure.FAILED)
            send(SpotEvent.Waiting(offBody = false))
            var moved = false
            val reading = withTimeoutOrNull(limitMillis(kind)) {
                for (batch in batches) {
                    if (batch.any { it.moving }) moved = true
                    // A completed reading from before the start would be one the platform kept.
                    batch.firstOrNull { it.percent != null && it.epochMillis >= startedAt - STALE_SPO2_MILLIS }?.let { return@withTimeoutOrNull it }
                }
                null
            }
            val percent = reading?.percent
            return when {
                percent != null -> SpotEvent.Result(percent.toDouble())
                errored.get() -> SpotEvent.Failed(SpotFailure.FAILED)
                moved -> SpotEvent.Failed(SpotFailure.MOVED)
                else -> SpotEvent.Failed(SpotFailure.NO_SIGNAL)
            }
        } finally {
            handler.post { source.stop() }
            worker.quitSafely()
            spo2Claim.release(CLAIM)
        }
    }

    /** A heart rate, an HRV or a blood oxygen goes to the entries, measured on the watch; the air pressure is a reading of the moment. */
    private fun keep(kind: SpotKind, result: SpotEvent.Result) {
        val type = when (kind) {
            SpotKind.HEART_RATE -> WearLinkEntry.Type.HEART_RATE
            SpotKind.HRV -> WearLinkEntry.Type.HRV
            SpotKind.SPO2 -> WearLinkEntry.Type.BLOOD_OXYGEN
            SpotKind.AIR_PRESSURE -> return
        }
        if (!entries.log(type, result.value, measured = true)) debug.count("spot_unlogged")
    }

    private companion object {
        const val TAG = "Spot"
        const val WAKE_LOCK_TAG = "OpenWatch:spot"
        const val MILLIS_PER_SECOND = 1_000L

        /** The spot's name on [SensorClaim]. */
        const val CLAIM = "spot"

        /** A completed SpO2 reading this much older than the start is the platform's last one, not this one. */
        const val STALE_SPO2_MILLIS = 5_000L

        /** A source that hands its result over late, the Samsung platform's, waits up to this after the window. */
        const val RESULT_WAIT_MILLIS = 30_000L

        /** Five a second for three minutes is 900; more than this is not kept. */
        const val MAX_MOTION_READINGS = 2_000

        /** A little past the measurement's own limit, so the lock never ends it early. */
        const val WAKE_LOCK_MARGIN_MILLIS = 10_000L
    }
}
