package tech.mmarca.openwatch.wear.samsung

import android.os.Handler
import tech.mmarca.openwatch.wear.SpotSource

/**
 * One of the Samsung platform's other trackers ([type]: SpO2, skin
 * temperature) as a [SpotSource], through the shared [SamsungHeartRateFeed];
 * [decode] turns the platform's points into the recorder's readings. Every
 * call and every delivery is on the handler [start] was given.
 */
internal class SamsungSpotSource<T>(
    private val feed: SamsungHeartRateFeed,
    private val type: String,
    private val decode: (RawPoint) -> T,
) : SpotSource<T>, SamsungHeartRateFeed.RawSubscriber {

    private var sink: SpotSource.Sink<T>? = null

    override val isAvailable: Boolean
        get() = feed.isAvailable

    override fun start(handler: Handler, sink: SpotSource.Sink<T>): Boolean {
        this.sink = sink
        if (!feed.subscribeRaw(type, this, handler)) {
            this.sink = null
            return false
        }
        return true
    }

    override fun stop() {
        feed.unsubscribeRaw(type, this)
        sink = null
    }

    override fun onRawPoints(points: List<RawPoint>) {
        sink?.onReadings(points.map(decode))
    }

    override fun onError() {
        sink?.onError()
    }

    companion object {
        fun spo2(feed: SamsungHeartRateFeed) = SamsungSpotSource(feed, SamsungHealthPlatform.TRACKER_SPO2, RawPoint::toSpo2Reading)

        fun skinTemperature(feed: SamsungHeartRateFeed) =
            SamsungSpotSource(feed, SamsungHealthPlatform.TRACKER_SKIN_TEMPERATURE, RawPoint::toSkinReading)
    }
}
