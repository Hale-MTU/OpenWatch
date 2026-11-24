package tech.mmarca.openwatch.wear.samsung

import android.os.Handler
import tech.mmarca.healthcalculations.signal.IbiPoint
import tech.mmarca.openwatch.wear.HeartRateSource
import tech.mmarca.openwatch.wear.SensorTime
import tech.mmarca.openwatch.wearlink.HeartRateInterval
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

/**
 * The heart rate from the Samsung Health Platform, through the shared
 * [SamsungHeartRateFeed]. The platform sets its own pace and batching; the
 * interval only switches it on and off. A platform that sends no point
 * within [FIRST_POINT_TIMEOUT_MILLIS], or reports an error, is a failure the
 * recorder answers by falling back to the sensor.
 */
internal class SamsungHeartRateSource(
    private val feed: SamsungHeartRateFeed,
    private val clock: () -> Long = System::currentTimeMillis,
) : HeartRateSource, SamsungHeartRateFeed.Subscriber {

    // On the handler's thread, which is where the feed delivers.
    private var handler: Handler? = null
    private var sink: HeartRateSource.Sink? = null
    private var subscribedAt = 0L
    private var delivered = false
    private val noDataTask = Runnable { sink?.onFailed("no_data") }

    override val isAvailable: Boolean
        get() = feed.isAvailable

    override fun start(interval: HeartRateInterval, handler: Handler, sink: HeartRateSource.Sink): Boolean {
        this.handler = handler
        this.sink = sink
        subscribedAt = clock()
        delivered = false
        if (!feed.subscribe(this, handler)) {
            this.handler = null
            this.sink = null
            return false
        }
        handler.postDelayed(noDataTask, FIRST_POINT_TIMEOUT_MILLIS)
        return true
    }

    override fun stop() {
        handler?.removeCallbacks(noDataTask)
        feed.unsubscribe(this)
        handler = null
        sink = null
    }

    override fun onPoints(points: List<IbiPoint>) {
        val target = sink ?: return
        if (!delivered && points.any { it.hasHeartRate }) {
            delivered = true
            handler?.removeCallbacks(noDataTask)
        }
        for (point in points) {
            // The first delivery can reach back before the subscription; that part is not this recording.
            if (point.epochMillis < subscribedAt - SensorTime.MAX_EVENT_AGE_MILLIS) continue
            when {
                point.hasHeartRate && point.bpm in WearLinkProtocol.MIN_BPM..WearLinkProtocol.MAX_BPM -> target.onReading(point.epochMillis, point.bpm)
                point.heartRateStatus < 0 -> target.onNoContact(point.epochMillis)
            }
        }
    }

    override fun onError() {
        sink?.onFailed("error")
    }

    private companion object {
        /** The platform's first point came after 125 s on a fresh boot; past this it is taken as silent. */
        const val FIRST_POINT_TIMEOUT_MILLIS = 5L * 60 * 1000
    }
}
