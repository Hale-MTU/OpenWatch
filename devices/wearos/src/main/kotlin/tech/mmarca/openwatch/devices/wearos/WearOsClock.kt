package tech.mmarca.openwatch.devices.wearos

import java.time.Duration

/**
 * The watch's clock against the phone's, from the time the watch put in its `OK`. The watch
 * stamps every row with its own clock, so a watch running ahead writes records into the future
 * and one corrected backwards writes them behind rows already sent; the sequence cursors carry
 * either, but the times are the watch's. Pure, apart from the one log line.
 */
internal object WearOsClock {

    /** Closer than this is the link's own delay and the two clocks' drift, nothing to tell. */
    val TOLERANCE: Duration = Duration.ofMinutes(2)

    /** How far the watch is ahead of the phone (negative: behind); null when it sent no clock. */
    fun skew(watchMillis: Long?, phoneMillis: Long): Duration? = watchMillis?.let { Duration.ofMillis(it - phoneMillis) }

    /** Logs, as a direction and not a value, a watch clock off by more than [TOLERANCE]. */
    fun check(watchMillis: Long?, phoneMillis: Long) {
        val skew = skew(watchMillis, phoneMillis) ?: return
        if (skew.abs() <= TOLERANCE) return
        WearOsLog.log(if (skew.isNegative) "watch clock behind the phone's by more than 2 minutes" else "watch clock ahead of the phone's by more than 2 minutes")
    }
}
