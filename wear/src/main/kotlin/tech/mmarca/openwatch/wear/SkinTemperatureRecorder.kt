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
    private val holdLastMillis: Long = 0L,
) : Recorder {

    private var thread: HandlerThread? = null

    /** The running recording: its own buffer, so a stop's flush never meets the next start's points. */
    private var session: Session? = null

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
        val next = Session(Handler(worker.looper), SkinMinuteBuffer(holdLastMillis))
        if (!source.start(next.handler, next)) {
            worker.quitSafely()
            return
        }
        thread = worker
        session = next
        if (holdLastMillis > 0L) next.handler.postDelayed(next.tick, TICK_MILLIS)
        Log.i(TAG, "Skin temperature on")
    }

    override fun stop() {
        val worker = thread ?: return
        val ending = session
        source.stop()
        // The open minute is written before the thread ends: quitSafely runs what is posted first.
        ending?.handler?.let { handler ->
            handler.removeCallbacks(ending.tick)
            handler.post { write(ending.buffer.flush(System.currentTimeMillis())) }
        }
        worker.quitSafely()
        thread = null
        session = null
        Log.i(TAG, "Skin temperature off")
    }

    /** One recording's sink, on its own worker thread. */
    private inner class Session(val handler: Handler, val buffer: SkinMinuteBuffer) : SpotSource.Sink<SkinReading> {

        /** Writes the held minutes as time passes; runs at the next wake-up when the watch sleeps. */
        val tick: Runnable = object : Runnable {
            override fun run() {
                write(buffer.tick(System.currentTimeMillis()))
                handler.postDelayed(this, TICK_MILLIS)
            }
        }

        override fun onError() {
            debug.event("Skin", "tracker_error")
        }

        override fun onReadings(readings: List<SkinReading>) {
            debug.count("skin.points", readings.size.toLong())
            write(buffer.add(readings))
        }
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

        /** How often the held minutes are written while the source is silent. */
        private const val TICK_MILLIS = MINUTE_MILLIS

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
 * tracker in batches. A minute closes when a point of a later minute arrives, at [tick] once its
 * time has passed, or at [flush].
 *
 * With [holdLastMillis] above zero the source is on-change (the Pixel sensor reports a 0.2 °C
 * step, and on a steady night can stay silent for most of an hour): its silence means the value
 * stands, so each silent minute repeats the last real reading, up to [holdLastMillis] after it.
 * The held minutes are written as time passes ([tick]), so a phone syncing inside a silence finds
 * them. A point that gives no row (one the sensor does not stand behind, or out of range) ends
 * the hold, and so does [flush] at the recorder's stop (off the wrist). One buffer per recording,
 * owned by its worker thread; not thread-safe.
 */
internal class SkinMinuteBuffer(private val holdLastMillis: Long = 0L) {

    private val pending = ArrayList<SkinReading>()

    /** The newest real reading that gave a row, which silent minutes repeat; null when nothing stands. */
    private var last: SkinReading? = null

    /** The newest minute written, by a row or a fill: the next fill starts after it. */
    private var writtenThrough = Long.MIN_VALUE

    /** Adds [points] and returns the minutes they closed, and the held minutes before the newest, oldest first. */
    fun add(points: List<SkinReading>): List<WearLinkProtocol.SkinMinute> {
        pending += points
        val newest = pending.maxOfOrNull { minuteOf(it.epochMillis) } ?: return emptyList()
        return closeBefore(newest)
    }

    /** The minutes before [nowMillis]'s minute, closed and held: what a sync now should find. */
    fun tick(nowMillis: Long): List<WearLinkProtocol.SkinMinute> = closeBefore(minuteOf(nowMillis))

    /** Every minute still open, the value held up to [nowMillis]'s minute; then empty, nothing standing. */
    fun flush(nowMillis: Long): List<WearLinkProtocol.SkinMinute> {
        val rows = closeBefore(maxOf(minuteOf(nowMillis), (pending.maxOfOrNull { minuteOf(it.epochMillis) } ?: Long.MIN_VALUE) + MINUTE_MILLIS))
        pending.clear()
        last = null
        writtenThrough = Long.MIN_VALUE
        return rows
    }

    /** Closes the pending minutes before [until], each after the held minutes before it, then holds up to [until]. */
    private fun closeBefore(until: Long): List<WearLinkProtocol.SkinMinute> {
        val (closed, open) = pending.partition { minuteOf(it.epochMillis) < until }
        pending.clear()
        pending += open
        val out = ArrayList<WearLinkProtocol.SkinMinute>()
        for ((minute, inMinute) in closed.groupBy { minuteOf(it.epochMillis) }.toSortedMap()) {
            out += held(before = minute)
            val row = SkinTemperatureRecorder.minutes(inMinute).singleOrNull()
            if (row == null) {
                // Nothing the sensor stands behind: the value no longer stands from here.
                last = null
                continue
            }
            out += row
            writtenThrough = maxOf(writtenThrough, minute)
            last = inMinute.filter(::givesRow).maxBy { it.epochMillis }
        }
        out += held(before = until)
        return out
    }

    /** The minutes after [writtenThrough] and before [before] that [last] still stands for, each repeating it. */
    private fun held(before: Long): List<WearLinkProtocol.SkinMinute> {
        val point = last ?: return emptyList()
        if (holdLastMillis <= 0L) return emptyList()
        val from = maxOf(writtenThrough, minuteOf(point.epochMillis)) + MINUTE_MILLIS
        // Measured from the real reading, never from a filled minute.
        val until = minOf(before, minuteOf(point.epochMillis + holdLastMillis) + MINUTE_MILLIS)
        if (from >= until) return emptyList()
        val rows = (from until until step MINUTE_MILLIS).flatMap { minute ->
            SkinTemperatureRecorder.minutes(listOf(SkinReading(minute, point.skinCelsius, point.ambientCelsius, point.measured)))
        }
        writtenThrough = until - MINUTE_MILLIS
        return rows
    }

    /** One test for "counts": the point makes a row. */
    private fun givesRow(point: SkinReading) = SkinTemperatureRecorder.minutes(listOf(point)).isNotEmpty()

    private fun minuteOf(epochMillis: Long) = epochMillis - epochMillis % MINUTE_MILLIS

    private companion object {
        const val MINUTE_MILLIS = 60_000L
    }
}
