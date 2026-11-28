package tech.mmarca.openwatch.wear.samsung

import android.os.Handler
import android.os.SystemClock
import tech.mmarca.healthcalculations.signal.NightHrv
import tech.mmarca.openwatch.wear.DebugProtocol
import tech.mmarca.openwatch.wear.HrvWindowSource
import tech.mmarca.openwatch.wear.NightWindowResult

/**
 * The green PPG of the Samsung Health Platform's continuous tracker for a
 * night-time HRV window: the same signal as the raw sensor
 * (`PpgWindowSource`), handed over by the platform instead, and analysed the
 * same way ([NightHrv]). The tracker runs only for the window. With the
 * screen off the platform batches its points and hands them over up to ten
 * minutes late, and a flush does not hurry them (see [SamsungIbiSource]), so
 * its answer closes nothing: after the window the source stays subscribed
 * until a point stamped after the window's end arrives, at most
 * [batchWaitMillis]. Only points whose status says the platform measured
 * them are kept ([PpgWindow]); a window without one counts as failed.
 *
 * Written from the platform's code, not yet run on a watch: the sample rate
 * the analysis needs (25 Hz) and the batching of this tracker are to be
 * measured.
 */
internal class SamsungPpgSource(
    private val feed: SamsungHeartRateFeed,
    private val debug: DebugProtocol,
    private val batchWaitMillis: Long = BATCH_WAIT_MILLIS,
) : HrvWindowSource, SamsungHeartRateFeed.RawSubscriber {

    private var handler: Handler? = null
    private var startedAt = 0L
    private var window = PpgWindow(0L)
    private var failed = false
    private var waiting: (() -> Unit)? = null
    private var waitingFor = 0L
    private var subscribed = false

    /** Whether the last window ended with a platform error or no sample at all: the platform is not serving. */
    override var lastWindowFailed: Boolean = false
        private set

    override val isAvailable: Boolean
        get() = feed.isAvailable

    override fun begin(handler: Handler, startMillis: Long): Boolean {
        cancel()
        this.handler = handler
        startedAt = startMillis
        window = PpgWindow(startMillis)
        failed = false
        subscribed = feed.subscribeRaw(
            SamsungHealthPlatform.TRACKER_PPG, this, handler,
            ppgColours = listOf(SamsungHealthPlatform.PPG_GREEN),
        )
        return subscribed
    }

    override fun onRawPoints(points: List<RawPoint>) {
        if (!subscribed) return
        if (window.add(points.map { it.toPpgPoint() }, waitingFor)) waiting?.invoke()
    }

    /** The flush's answer is no sign that every point arrived; the window waits for one past its end. */
    override fun onFlushed() = Unit

    override fun onError() {
        failed = true
    }

    override fun finish(endMillis: Long, onResult: (NightWindowResult) -> Unit) {
        val workerHandler = handler
        if (!subscribed || workerHandler == null) return onResult(NightWindowResult(null))
        val askedAt = SystemClock.elapsedRealtime()
        var done = false
        val deliver = deliver@{
            if (done) return@deliver
            done = true
            val t = window.times()
            val v = window.values()
            val invalid = window.invalid
            val began = startedAt
            val hadError = failed
            lastWindowFailed = hadError || t.isEmpty()
            cancel()
            val result = if (hadError) null else NightHrv.analyze(t, v)
            val breathing = if (hadError) null else NightHrv.respiration(t, v)
            debug.event(
                "NightHrv", "samsung_ppg_window",
                "startedAt" to began,
                "waitMs" to SystemClock.elapsedRealtime() - askedAt,
                "samples" to t.size,
                "invalid" to invalid,
                "error" to hadError,
                "rmssd" to result?.rmssdMillis,
                "beats" to result?.beatCount,
                "rr" to breathing?.value,
            )
            onResult(NightWindowResult(result, breathing?.value, breathing?.routes ?: 0))
        }
        if (window.coversEnd(endMillis)) return deliver()
        waiting = deliver
        waitingFor = endMillis
        // A flush may still bring the newest points sooner; the answer itself is not waited for.
        feed.flush(SamsungHealthPlatform.TRACKER_PPG)
        workerHandler.postAtTime(deliver, FLUSH_TOKEN, SystemClock.uptimeMillis() + batchWaitMillis)
    }

    override fun cancel() {
        if (subscribed) feed.unsubscribeRaw(SamsungHealthPlatform.TRACKER_PPG, this)
        subscribed = false
        waiting = null
        waitingFor = 0L
        handler?.removeCallbacksAndMessages(FLUSH_TOKEN)
        window = PpgWindow(0L)
        startedAt = 0L
    }

    private companion object {
        val FLUSH_TOKEN = Any()

        /** The platform's batches came up to ten minutes late; past this the points in hand are used. */
        const val BATCH_WAIT_MILLIS = 12L * 60 * 1000
    }
}

/**
 * The green PPG points of one window: those from inside it that the platform measured
 * ([PpgPoint.greenStatus] 0), in the capacity the analysis needs (25 Hz for five minutes is
 * 7 500; more is not kept). Points of another status are counted in [invalid], not kept: if
 * the layout of the platform's answer were read wrong, every point would read 0 with status -1,
 * and an empty window is a failed one, which the night's HRV falls back from.
 */
internal class PpgWindow(private val startMillis: Long) {
    // Only a window that takes points holds the room for them.
    private val times by lazy { DoubleArray(MAX_SAMPLES) }
    private val values by lazy { DoubleArray(MAX_SAMPLES) }
    private var count = 0
    private var newest = Long.MIN_VALUE

    /** Points seen from inside the window whose status was not a measurement. */
    var invalid = 0
        private set

    /**
     * Takes [points]; [endMillis] is the window's end once it is asked for, else 0. True when one is
     * stamped at or after the end (the batch holding the window's last points has arrived).
     */
    fun add(points: List<PpgPoint>, endMillis: Long): Boolean {
        var covered = false
        for (point in points) {
            newest = maxOf(newest, point.epochMillis)
            if (endMillis != 0L && point.epochMillis >= endMillis) covered = true
            // A reading the platform kept from before the window, or one past it.
            if (point.epochMillis < startMillis || (endMillis != 0L && point.epochMillis > endMillis)) continue
            if (point.greenStatus != STATUS_MEASURED) {
                invalid++
                continue
            }
            if (count >= MAX_SAMPLES) continue
            times[count] = point.epochMillis.toDouble()
            values[count] = point.green.toDouble()
            count++
        }
        return covered
    }

    /** Whether a point stamped at or after [endMillis] has come. */
    fun coversEnd(endMillis: Long): Boolean = newest >= endMillis

    fun times(): DoubleArray = if (count == 0) DoubleArray(0) else times.copyOf(count)

    fun values(): DoubleArray = if (count == 0) DoubleArray(0) else values.copyOf(count)

    companion object {
        const val MAX_SAMPLES = 25 * 330

        /** The platform's status for a point it measured. */
        const val STATUS_MEASURED = 0
    }
}
