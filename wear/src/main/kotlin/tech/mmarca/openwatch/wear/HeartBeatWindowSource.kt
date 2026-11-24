package tech.mmarca.openwatch.wear

import android.os.Handler

/**
 * A window of single beats from a heart-beat sensor (`TYPE_HEART_BEAT`,
 * [HeartBeatSensor]), for a spot HRV on a watch that reports each beat: the
 * beats' times between [begin] and [finish], and their RMSSD by the spot's
 * rule ([SpotRules.rmssd], health-calculations' night-window RMSSD on fewer
 * beats). The sensor gives no breathing rate.
 */
internal class HeartBeatWindowSource(private val sensor: SpotSensor<Double>) : HrvWindowSource {

    private val beats = ArrayList<Double>()
    private var handle: AutoCloseable? = null

    override val isAvailable: Boolean = true

    override fun begin(handler: Handler, startMillis: Long): Boolean {
        cancel()
        handle = sensor.listen { beat -> synchronized(beats) { if (beats.size < MAX_BEATS) beats += beat } }
        return handle != null
    }

    override fun finish(endMillis: Long, onResult: (NightWindowResult) -> Unit) {
        val times = synchronized(beats) { beats.sorted() }
        cancel()
        onResult(NightWindowResult(SpotRules.rmssdWindow(times)))
    }

    override fun cancel() {
        handle?.close()
        handle = null
        synchronized(beats) { beats.clear() }
    }

    private companion object {
        /** Three minutes at the fastest pulse the intervals allow, 200 bpm, with room to spare. */
        const val MAX_BEATS = 1_000
    }
}
