package tech.mmarca.openwatch.wear

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlin.math.roundToInt
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

/**
 * Skin temperature at night, one [WearMetrics.SKIN_TEMPERATURE] row per
 * clock minute: the mean of the minute's good points, skin and ambient. The
 * points come from a [SpotSource]: the raw Pixel sensor on a Pixel Watch,
 * the Samsung Health Platform's tracker elsewhere ([WearGraph] chooses).
 * A watch with neither has no skin temperature.
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
    /** Above zero for an on-change source: how long its silence repeats the last value ([SkinMinuteBuffer]). */
    holdLastMillis: Long = 0L,
) : Recorder, SpotSource.Sink<SkinReading> {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** Read and written on the worker thread only. */
    private val buffer = SkinMinuteBuffer(holdLastMillis)

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
        val workerHandler = Handler(worker.looper)
        if (!source.start(workerHandler, this)) {
            worker.quitSafely()
            return
        }
        thread = worker
        handler = workerHandler
        Log.i(TAG, "Skin temperature on")
    }

    override fun stop() {
        val worker = thread ?: return
        source.stop()
        // The open minute is written before the thread ends: quitSafely runs what is posted first.
        handler?.post { write(buffer.flush(System.currentTimeMillis())) }
        worker.quitSafely()
        thread = null
        handler = null
        Log.i(TAG, "Skin temperature off")
    }

    override fun onError() {
        debug.event("Skin", "tracker_error")
    }

    override fun onReadings(readings: List<SkinReading>) {
        debug.count("skin.points", readings.size.toLong())
        write(buffer.add(readings))
    }

    /** Each closed minute once. */
    private fun write(rows: List<WearLinkProtocol.SkinMinute>) {
        if (rows.isEmpty()) return
        debug.count("skin.rows", rows.size.toLong())
        store.putAll(WearMetrics.SKIN_TEMPERATURE, rows)
        store.prune(WearMetrics.SKIN_TEMPERATURE, System.currentTimeMillis())
    }

    companion object {
        private const val TAG = "SkinTemperatureRecorder"
        private const val MINUTE_MILLIS = 60_000L

        /**
         * What a minute with no ambient at all stamps into the row. The wire
         * line demands an ambient value in range, and the raw Pixel skin
         * channel is skin-only; the placeholder is for the round-trip check
         * and the phone's parser only — [tech.mmarca.openwatch.devices.wearos.WearSeries]
         * maps just the skin Celsius into Health Connect, so the phone side
         * discards ambient and this number never reaches it. Mid-range keeps
         * it inside the protocol's bounds whatever a future reader assumes.
         */
        internal const val PLACEHOLDER_AMBIENT100 = 2300

        /** The minutes the good points fall in, each the mean of its points. Pure. */
        internal fun minutes(points: List<SkinReading>): List<WearLinkProtocol.SkinMinute> =
            points.filter { it.measured && !it.skinCelsius.isNaN() }
                .groupBy { it.epochMillis - it.epochMillis % MINUTE_MILLIS }
                .mapNotNull { (minute, inMinute) ->
                    val skin = (inMinute.map { it.skinCelsius }.average() * 100).roundToInt()
                    // Average only the minutes' real ambients; a skin-only channel says nothing about ambient.
                    val ambients = inMinute.map { it.ambientCelsius }.filter { !it.isNaN() }
                    val ambient = if (ambients.isEmpty()) PLACEHOLDER_AMBIENT100 else (ambients.average() * 100).roundToInt()
                    val row = WearLinkProtocol.SkinMinute(minute, skin, ambient)
                    row.takeIf { WearLinkProtocol.parseSkinMinute(WearLinkProtocol.formatSkinMinute(it)) != null }
                }
                .sortedBy { it.epochMillis }
    }
}

/**
 * Collects skin points until their minute closes, so a minute is written once, as the mean of all
 * its points, however the source hands them over: the Pixel sensor one at a time, the Samsung
 * tracker in batches. A minute closes when a point of a later minute arrives, or at [flush].
 *
 * With [holdLastMillis] above zero the source is on-change (the Pixel sensor reports a 0.2 °C
 * step, and on a steady night can stay silent for most of an hour): its silence means the value
 * stands, so each silent minute repeats the last point, for at most [holdLastMillis] after it.
 * The recorder runs only on the wrist, and [flush] at its stop forgets the value, so nothing is
 * held across time off the wrist. Not thread-safe: the recorder's worker thread owns it.
 */
internal class SkinMinuteBuffer(private val holdLastMillis: Long = 0L) {

    private val pending = ArrayList<SkinReading>()

    /** The last good point of the newest closed minute, which silent minutes repeat. */
    private var last: SkinReading? = null

    /** Adds [points] and returns the minutes they closed, oldest first. */
    fun add(points: List<SkinReading>): List<WearLinkProtocol.SkinMinute> {
        pending += points
        val newest = pending.maxOfOrNull { minuteOf(it.epochMillis) } ?: return emptyList()
        val (closed, open) = pending.partition { minuteOf(it.epochMillis) < newest }
        pending.clear()
        pending += open
        return close(closed, untilMinute = if (open.any(::isGood)) newest else null)
    }

    /** Every minute still open, the value held up to [nowMillis]'s minute; then empty and forgotten. */
    fun flush(nowMillis: Long): List<WearLinkProtocol.SkinMinute> {
        val rows = close(pending.toList(), untilMinute = minuteOf(nowMillis))
        pending.clear()
        last = null
        return rows
    }

    /** [points]' minutes, the silent minutes before each, and before [untilMinute], holding the last value. */
    private fun close(points: List<SkinReading>, untilMinute: Long?): List<WearLinkProtocol.SkinMinute> {
        val rows = SkinTemperatureRecorder.minutes(points)
        if (holdLastMillis <= 0L) return rows
        val good = points.filter(::isGood)
        val out = ArrayList<WearLinkProtocol.SkinMinute>()
        for (row in rows) {
            out += held(before = row.epochMillis)
            out += row
            last = good.filter { minuteOf(it.epochMillis) == row.epochMillis }.maxBy { it.epochMillis }
        }
        if (untilMinute != null) out += held(before = untilMinute)
        return out
    }

    /**
     * The silent minutes from [last]'s next minute up to [before], each repeating [last]; none
     * when the silence runs longer than [holdLastMillis]. Fills only reach a minute that a point
     * or the stop closes, so the silence is always measured from a real point.
     */
    private fun held(before: Long): List<WearLinkProtocol.SkinMinute> {
        val point = last ?: return emptyList()
        val from = minuteOf(point.epochMillis) + MINUTE_MILLIS
        if (before <= from || before - point.epochMillis > holdLastMillis + MINUTE_MILLIS) return emptyList()
        val rows = (from until before step MINUTE_MILLIS).flatMap { minute ->
            SkinTemperatureRecorder.minutes(listOf(SkinReading(minute, point.skinCelsius, point.ambientCelsius, point.measured)))
        }
        // The filled minutes are closed: the next fill starts after them, from the same point.
        last = SkinReading(before - MINUTE_MILLIS, point.skinCelsius, point.ambientCelsius, point.measured)
        return rows
    }

    private fun isGood(point: SkinReading) = point.measured && !point.skinCelsius.isNaN()

    private fun minuteOf(epochMillis: Long) = epochMillis - epochMillis % MINUTE_MILLIS

    private companion object {
        const val MINUTE_MILLIS = 60_000L
    }
}
