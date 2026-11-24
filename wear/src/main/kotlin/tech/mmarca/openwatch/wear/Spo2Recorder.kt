package tech.mmarca.openwatch.wear

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

/**
 * SpO2 spot measurements at night, one [WearMetrics.SPO2] row each, from a
 * [SpotSource]: the Samsung Health Platform's tracker, an on-demand
 * measurement (red and infrared light, a still arm): started, it reports
 * "completed" with a value after about 13 seconds on a Galaxy Watch7, then
 * "ended"; a moving arm reports "moving" instead.
 *
 * A measurement starts only while sleep is held ([isHolding]), the wearer
 * switched it on ([WearSettings.spo2AtNight]), and the last ten minute rows
 * show a still arm ([NightHrvSchedule.mayStart]), at most every
 * [MIN_GAP_MILLIS]; after a measurement the arm spoiled, the next try comes
 * after [RETRY_GAP_MILLIS]. It holds a wake lock for at most
 * [MEASUREMENT_TIMEOUT_MILLIS]. There is no other source: the red and
 * infrared channels are closed to ordinary apps.
 */
internal class Spo2Recorder(
    context: Context,
    private val store: MetricStore,
    private val source: SpotSource<Spo2Reading>,
    private val debug: DebugProtocol,
    /** Whether sleep is held now; read on the recorder's thread. */
    private val isHolding: () -> Boolean,
) : Recorder, SpotSource.Sink<Spo2Reading> {

    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        .apply { setReferenceCounted(false) }
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val tick = Runnable { onTick() }
    private val timeout = Runnable { endMeasurement(null, "timeout") }

    // Recorder thread only.
    private var nextAllowedAt = 0L
    private var measuringSince = 0L
    private var sawMoving = false

    val isAvailable: Boolean
        get() = source.isAvailable

    override val isRunning: Boolean
        get() = thread != null

    override fun apply(plan: RecorderPlan.Plan) = setEnabled(plan.spo2)

    /** Starts or stops the recorder. Main thread. */
    fun setEnabled(enabled: Boolean) {
        if (enabled && source.isAvailable) start() else stop()
    }

    private fun start() {
        if (thread != null) return
        val worker = HandlerThread("Spo2Recorder").apply { start() }
        val workerHandler = Handler(worker.looper)
        thread = worker
        handler = workerHandler
        workerHandler.post {
            store.latest(WearMetrics.SPO2)?.let { nextAllowedAt = it.epochMillis + MIN_GAP_MILLIS }
            workerHandler.postDelayed(tick, TICK_MILLIS)
        }
        Log.i(TAG, "SpO2 at night on")
    }

    override fun stop() {
        val worker = thread ?: return
        handler?.removeCallbacks(tick)
        handler?.post { endMeasurement(null, "stopped") }
        worker.quitSafely()
        thread = null
        handler = null
        Log.i(TAG, "SpO2 at night off")
    }

    private fun onTick() {
        val workerHandler = handler ?: return
        val now = System.currentTimeMillis()
        if (measuringSince == 0L && isHolding() && now >= nextAllowedAt) {
            val rows = store.since(WearMetrics.SLEEP_MINUTES, now - LOOKBACK_MILLIS, LOOKBACK_ROWS)
            if (NightHrvSchedule.mayStart(rows, now, nextAllowedAt)) beginMeasurement(now)
        }
        workerHandler.postDelayed(tick, TICK_MILLIS)
    }

    private fun beginMeasurement(now: Long) {
        val workerHandler = handler ?: return
        if (!source.start(workerHandler, this)) {
            nextAllowedAt = now + RETRY_GAP_MILLIS
            debug.event("Spo2", "refused")
            return
        }
        measuringSince = now
        sawMoving = false
        wakeLock.acquire(MEASUREMENT_TIMEOUT_MILLIS + WAKE_LOCK_SLACK_MILLIS)
        workerHandler.postDelayed(timeout, MEASUREMENT_TIMEOUT_MILLIS)
        debug.event("Spo2", "started")
    }

    override fun onError() {
        endMeasurement(null, "tracker_error")
    }

    override fun onReadings(readings: List<Spo2Reading>) {
        if (measuringSince == 0L) return
        if (readings.any { it.moving }) sawMoving = true
        // A completed reading from before this measurement would be an old one the platform kept.
        val done = readings.firstOrNull { it.percent != null && it.epochMillis >= measuringSince - STALE_MILLIS } ?: return
        endMeasurement(done, "completed")
    }

    private fun endMeasurement(result: Spo2Reading?, why: String) {
        val started = measuringSince
        if (started == 0L) return
        measuringSince = 0L
        handler?.removeCallbacks(timeout)
        source.stop()
        if (wakeLock.isHeld) wakeLock.release()
        val now = System.currentTimeMillis()
        debug.event(
            "Spo2", "ended",
            "why" to why, "afterMs" to now - started, "moving" to sawMoving,
            "percent" to result?.percent, "bpm" to result?.bpm,
        )
        val percent = result?.percent
        if (result == null || percent == null) {
            nextAllowedAt = now + RETRY_GAP_MILLIS
            return
        }
        nextAllowedAt = started + MIN_GAP_MILLIS
        val bpm = result.bpm.takeIf { it in WearLinkProtocol.MIN_BPM..WearLinkProtocol.MAX_BPM }
        store.put(WearMetrics.SPO2, WearLinkProtocol.Spo2Measurement(result.epochMillis, percent, bpm))
        store.prune(WearMetrics.SPO2, now)
    }

    private companion object {
        const val TAG = "Spo2Recorder"
        const val WAKE_LOCK_TAG = "OpenWatch:spo2"
        const val TICK_MILLIS = 60_000L

        /** One measurement an hour: enough for a night's curve, little light and wake time. */
        const val MIN_GAP_MILLIS = 60L * 60 * 1000
        const val RETRY_GAP_MILLIS = 15L * 60 * 1000

        /** A measurement completed after 13 s on the bench; past a minute it is given up. */
        const val MEASUREMENT_TIMEOUT_MILLIS = 60_000L
        const val WAKE_LOCK_SLACK_MILLIS = 10_000L
        const val STALE_MILLIS = 5_000L

        const val LOOKBACK_MILLIS = 30L * 60 * 1000
        const val LOOKBACK_ROWS = 40
    }
}
