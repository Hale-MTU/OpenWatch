package tech.mmarca.openwatch.wear

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import tech.mmarca.openwatch.wearlink.HeartRateInterval
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

/**
 * Keeps the heart rate measured at the interval in force and writes what it
 * reports to the [MetricStore]. Runs inside `WearAppService`, so it outlives
 * the activity. The power-save interval and its alarm come from OpenVitals.
 *
 * The readings come from a [HeartRateSource]: the Android sensor ([sensor],
 * every watch) or the Samsung Health Platform ([platform], Samsung watches
 * only, [WearSettings.Source.SAMSUNG_PLATFORM]). The platform is used only
 * when chosen; when it refuses, fails or stays silent, the sensor takes over
 * at the same interval until a setting changes ([resetFallBack]). One stored
 * sample every ten seconds from either; while the plan asks for the heart rate
 * live (a workout), the sensor's every reading, one a second.
 *
 * [setPlan] is the only entry point; the service calls it with the plan in
 * force, and an unchanged one does nothing. The source runs on the
 * recorder's own thread, where the SQLite write is allowed. [onAlarmFired]
 * is the exception: `WearAppService` calls it on its own thread, and the
 * source only posts to the recorder's thread.
 */
internal class HeartRateRecorder(
    private val store: MetricStore,
    private val sensor: HeartRateSource,
    /** The Samsung platform's source; null on a watch without it. */
    private val platform: HeartRateSource?,
    private val debug: DebugProtocol,
    /** The source in use changed by itself (a fall-back to the sensor). Called on the recorder's thread. */
    private val onSourceChanged: () -> Unit = {},
    /** Every stored sample, for the sleep minute recorder. Called on this recorder's thread. */
    private val onSample: (epochMillis: Long, bpm: Int) -> Unit = { _, _ -> },
    /** False when the sensor reported no contact, an unreliable reading or 0 bpm. */
    private val onContact: (epochMillis: Long, contact: Boolean) -> Unit = { _, _ -> },
) : Recorder, HeartRateSource.Sink {

    private var thread: HandlerThread? = null

    /** Set with [thread]; the source runs on it. */
    @Volatile
    private var handler: Handler? = null

    /** The interval this recorder runs at; [HeartRateInterval.OFF] while stopped. Read on any thread. */
    @Volatile
    var interval: HeartRateInterval = HeartRateInterval.OFF
        private set

    /** The source in use while running. Read on any thread. */
    @Volatile
    internal var source: WearSettings.Source = WearSettings.Source.RAW_SENSOR
        private set

    /** The source the plan asked for; [source] differs from it after a fall-back. */
    @Volatile
    private var requestedSource: WearSettings.Source = WearSettings.Source.RAW_SENSOR

    /** What the plan asked for: the readings live, unbatched, and every one kept ([RecorderPlan.Plan.heartRateLive]). */
    @Volatile
    private var live = false

    /** Whether the recorder runs live. Read on any thread. */
    val isLive: Boolean
        get() = live && thread != null

    /** Set when the Samsung platform failed; the sensor stands in until [resetFallBack]. */
    @Volatile
    private var platformFailed = false

    private var lastStoredAt = 0L
    private var lastPrunedAt = 0L

    override val isRunning: Boolean
        get() = thread != null

    /** Whether the heart rate arrives every few seconds and wakes the processor: the full rate from the sensor. Any thread. */
    val isDense: Boolean
        get() = interval == HeartRateSchedule.FULL_RATE && source == WearSettings.Source.RAW_SENSOR

    override fun apply(plan: RecorderPlan.Plan) {
        setPlan(plan.heartRate, plan.heartRateSource, plan.heartRateLive)
    }

    /**
     * Brings the recorder to [wanted] from [wantedSource], [live] or batched:
     * starts it, changes its interval, source or pace, or stops it. False when
     * [wanted] is not off and the source could not start (no sensor, a refused
     * registration or bind).
     */
    internal fun setPlan(wanted: HeartRateInterval, wantedSource: WearSettings.Source, live: Boolean = false): Boolean {
        val running = wanted != HeartRateInterval.OFF && thread != null
        val unchanged = wanted == interval && (wanted == HeartRateInterval.OFF || (running && wantedSource == requestedSource && live == isLive))
        if (unchanged) return true
        stop()
        requestedSource = wantedSource
        this.live = live && wanted != HeartRateInterval.OFF
        if (wanted == HeartRateInterval.OFF) return true
        val usePlatform = wantedSource == WearSettings.Source.SAMSUNG_PLATFORM && platform?.isAvailable == true && !platformFailed
        val started = if (usePlatform) startPlatform(wanted) else startSensor(wanted)
        debug.event("HeartRate", "plan", "intervalSec" to wanted.seconds, "source" to source.name, "live" to live, "started" to started)
        return started
    }

    private fun startSensor(wanted: HeartRateInterval): Boolean {
        if (!sensor.isAvailable) {
            Log.w(TAG, "No heart rate sensor")
            return false
        }
        val workerHandler = startThread()
        interval = wanted
        source = WearSettings.Source.RAW_SENSOR
        if (!sensor.start(wanted, workerHandler, this, live)) {
            Log.w(TAG, "Heart rate listener not registered")
            stop()
            return false
        }
        Log.i(TAG, "Recording heart rate from the sensor")
        return true
    }

    private fun startPlatform(wanted: HeartRateInterval): Boolean {
        val samsung = platform ?: return startSensor(wanted)
        val workerHandler = startThread()
        interval = wanted
        source = WearSettings.Source.SAMSUNG_PLATFORM
        if (!samsung.start(wanted, workerHandler, this, live)) {
            Log.w(TAG, "Samsung platform refused")
            stop()
            platformFailed = true
            debug.event("HeartRate", "fallback", "why" to "bind_refused")
            return startSensor(wanted)
        }
        Log.i(TAG, "Recording heart rate from the Samsung platform")
        return true
    }

    private fun startThread(): Handler {
        val worker = HandlerThread("HeartRateRecorder").apply { start() }
        val workerHandler = Handler(worker.looper)
        thread = worker
        handler = workerHandler
        return workerHandler
    }

    override fun stop() {
        val worker = thread ?: return
        handler?.removeCallbacksAndMessages(null)
        sensor.stop()
        platform?.stop()
        worker.quitSafely()
        thread = null
        handler = null
        interval = HeartRateInterval.OFF
        Log.i(TAG, "Stopped recording heart rate")
    }

    /**
     * An alarm fired while this recorder was [HeartRateInterval.POWER_SAVE].
     * Called by `WearAppService` on its own thread; the source only posts to
     * the recorder's thread. A no-op once the interval or the source has since
     * changed: cancelling the alarm races the alarm already having fired.
     */
    fun onAlarmFired() {
        if (handler == null || interval != HeartRateInterval.POWER_SAVE || source != WearSettings.Source.RAW_SENSOR) return
        sensor.onAlarm()
    }

    override fun onReading(epochMillis: Long, bpm: Int) = keep(epochMillis, bpm)

    override fun onNoContact(epochMillis: Long) = onContact(epochMillis, false)

    /** The Samsung platform failed or stayed silent: the sensor takes over at the same interval. Recorder thread. */
    override fun onFailed(why: String) {
        if (source != WearSettings.Source.SAMSUNG_PLATFORM || thread == null) return
        val keep = interval
        Log.w(TAG, "Samsung platform failed, falling back to the sensor")
        debug.event("HeartRate", "fallback", "why" to why)
        platformFailed = true
        stop()
        startSensor(keep)
        onSourceChanged()
    }

    /** The wearer changed a setting: the Samsung platform may be tried again. */
    fun resetFallBack() {
        platformFailed = false
    }

    private fun keep(at: Long, bpm: Int) {
        if (at - lastStoredAt < if (live) LIVE_SAMPLE_GAP_MILLIS else MIN_SAMPLE_GAP_MILLIS) return
        lastStoredAt = at
        store.put(WearMetrics.HEART_RATE, WearLinkProtocol.HeartRateSample(at, bpm))
        onSample(at, bpm)
        debug.count("hr.stored")
        debug.max("hr.lagMs.max", System.currentTimeMillis() - at)
        if (at - lastPrunedAt > PRUNE_EVERY_MILLIS) {
            lastPrunedAt = at
            store.prune(WearMetrics.HEART_RATE, at)
        }
    }

    private companion object {
        const val TAG = "HeartRateRecorder"

        /** Stored sample spacing. Enough for resting and daily curves; a tenth of the bytes. */
        const val MIN_SAMPLE_GAP_MILLIS = 10_000L

        /**
         * The spacing while live: every reading of the 1 Hz sensor, a little early or late. A
         * workout's zones and figures read the whole curve, and a heart rate recovery needs a
         * reading within seconds of its marks. [TileDay.MAX_HEART_RATE_SAMPLES_PER_DAY] counts on it.
         */
        const val LIVE_SAMPLE_GAP_MILLIS = 900L

        const val PRUNE_EVERY_MILLIS = 6L * 60 * 60 * 1000
    }
}
