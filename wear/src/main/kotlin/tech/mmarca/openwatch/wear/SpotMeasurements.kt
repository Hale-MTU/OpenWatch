package tech.mmarca.openwatch.wear

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull
import tech.mmarca.openwatch.wearlink.WearLinkEntry

/**
 * The spot measurements the wearer starts by hand ([SpotKind]), built once
 * in [WearGraph] over the sources it hands in, so no screen opens a sensor:
 * the heart rate through [LiveHeartRate.listen], the single beats of a
 * heart-beat sensor for the HRV, the barometer once a second for the air
 * pressure. Each source is asked for when a measurement starts, off the main
 * thread, and is null where the watch lacks its sensor.
 *
 * [run] holds a partial wake lock of its own for as long as a measurement
 * lasts, so it ends with its reading even when the screen goes off. A heart
 * rate and an HRV are logged as entries measured on the watch ([EntryLog],
 * flag `m`); the air pressure is shown, not kept.
 */
internal class SpotMeasurements(
    context: Context,
    private val heartRate: () -> SpotSensor<LiveHeartRate.Reading>?,
    private val heartBeats: () -> SpotSensor<Double>?,
    private val pressure: () -> SpotSensor<Float>?,
    private val heartRateGranted: () -> Boolean,
    private val entries: EntryLog,
    private val debug: DebugProtocol,
    /** How long a measurement of a kind may take, milliseconds; its own limit but in a test. */
    private val limitMillis: (SpotKind) -> Long = { it.seconds * MILLIS_PER_SECOND },
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
            acquire(limitMillis(kind) + WAKE_LOCK_MARGIN_MILLIS)
        }
        debug.event(TAG, "start", "kind" to kind.name)
        try {
            val end = when (kind) {
                SpotKind.HEART_RATE -> heartRate(kind)
                SpotKind.HRV -> hrv(kind)
                SpotKind.AIR_PRESSURE -> airPressure(kind)
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

    /** The RMSSD of the kind's whole minute of single beats, the pulse between two beats as the live value. */
    private suspend fun ProducerScope<SpotEvent>.hrv(kind: SpotKind): SpotEvent {
        val sensor = heartBeats() ?: return SpotEvent.Failed(SpotFailure.NOT_SUPPORTED)
        if (!heartRateGranted()) return SpotEvent.Failed(SpotFailure.PERMISSION_DENIED)
        val beats = Channel<Double>(Channel.UNLIMITED)
        val handle = sensor.listen { beats.trySend(it) } ?: return SpotEvent.Failed(SpotFailure.FAILED)
        try {
            val times = ArrayList<Double>()
            withTimeoutOrNull(limitMillis(kind)) {
                for (beat in beats) {
                    times.lastOrNull()?.let { SpotRules.beatBpm(it, beat) }?.let { send(SpotEvent.Live(it)) }
                    times += beat
                }
            }
            return SpotRules.rmssd(times)?.let { SpotEvent.Result(it) } ?: SpotEvent.Failed(SpotFailure.NO_SIGNAL)
        } finally {
            handle.close()
        }
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

    /** A heart rate or an HRV goes to the entries, measured on the watch; the air pressure is a reading of the moment. */
    private fun keep(kind: SpotKind, result: SpotEvent.Result) {
        val type = when (kind) {
            SpotKind.HEART_RATE -> WearLinkEntry.Type.HEART_RATE
            SpotKind.HRV -> WearLinkEntry.Type.HRV
            SpotKind.AIR_PRESSURE -> return
        }
        if (!entries.log(type, result.value, measured = true)) debug.count("spot_unlogged")
    }

    private companion object {
        const val TAG = "Spot"
        const val WAKE_LOCK_TAG = "OpenWatch:spot"
        const val MILLIS_PER_SECOND = 1_000L

        /** A little past the measurement's own limit, so the lock never ends it early. */
        const val WAKE_LOCK_MARGIN_MILLIS = 10_000L
    }
}
