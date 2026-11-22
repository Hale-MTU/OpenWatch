package tech.mmarca.openwatch.wear

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlin.math.roundToInt
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

/**
 * Skin temperature at night, one [WearMetrics.SKIN_TEMPERATURE] row per
 * clock minute: the mean of the minute's good points, skin and ambient. The
 * points come from a [SpotSource]: the Samsung Health Platform's tracker, the
 * only one, since the sensor itself is closed to ordinary apps; a watch
 * without the platform has no skin temperature.
 *
 * Runs only while sleep is held and the wearer switched it on
 * ([WearSettings.skinTemperature]). In the bench test on a Galaxy Watch7 the
 * tracker delivered nothing; the debug protocol counts what arrives
 * (`samsung.skinPoints`, `skin.*`).
 */
internal class SkinTemperatureRecorder(
    private val store: MetricStore,
    private val source: SpotSource<SkinReading>,
    private val debug: DebugProtocol,
) : Recorder, SpotSource.Sink<SkinReading> {

    private var thread: HandlerThread? = null

    val isAvailable: Boolean
        get() = source.isAvailable

    override val isRunning: Boolean
        get() = thread != null

    override fun apply(plan: RecorderPlan.Plan) = setEnabled(plan.skinTemperature)

    /** Starts or stops the recorder. Main thread. */
    fun setEnabled(enabled: Boolean) {
        if (enabled && source.isAvailable) start() else stop()
    }

    private fun start() {
        if (thread != null) return
        val worker = HandlerThread("SkinTemperatureRecorder").apply { start() }
        if (!source.start(Handler(worker.looper), this)) {
            worker.quitSafely()
            return
        }
        thread = worker
        Log.i(TAG, "Skin temperature on")
    }

    override fun stop() {
        val worker = thread ?: return
        source.stop()
        worker.quitSafely()
        thread = null
        Log.i(TAG, "Skin temperature off")
    }

    override fun onError() {
        debug.event("Skin", "tracker_error")
    }

    override fun onReadings(readings: List<SkinReading>) {
        val rows = minutes(readings)
        debug.count("skin.points", readings.size.toLong())
        debug.count("skin.rows", rows.size.toLong())
        if (rows.isEmpty()) return
        store.putAll(WearMetrics.SKIN_TEMPERATURE, rows)
        store.prune(WearMetrics.SKIN_TEMPERATURE, System.currentTimeMillis())
    }

    companion object {
        private const val TAG = "SkinTemperatureRecorder"
        private const val MINUTE_MILLIS = 60_000L

        /** The minutes the good points fall in, each the mean of its points. Pure. */
        internal fun minutes(points: List<SkinReading>): List<WearLinkProtocol.SkinMinute> =
            points.filter { it.measured && !it.skinCelsius.isNaN() && !it.ambientCelsius.isNaN() }
                .groupBy { it.epochMillis - it.epochMillis % MINUTE_MILLIS }
                .mapNotNull { (minute, inMinute) ->
                    val skin = (inMinute.map { it.skinCelsius }.average() * 100).roundToInt()
                    val ambient = (inMinute.map { it.ambientCelsius }.average() * 100).roundToInt()
                    val row = WearLinkProtocol.SkinMinute(minute, skin, ambient)
                    row.takeIf { WearLinkProtocol.parseSkinMinute(WearLinkProtocol.formatSkinMinute(it)) != null }
                }
                .sortedBy { it.epochMillis }
    }
}
