package tech.mmarca.openwatch.wear

import java.util.concurrent.atomic.AtomicReference

/**
 * One user of a sensor at a time, where two would fight over it: the night's
 * HRV window and a spot HRV the wearer starts both read the raw pulse signal
 * or the Samsung platform's intervals for minutes. The one that comes second
 * does not start ([take] is false): the night recorder tries again at its
 * next tick, a spot measurement says the watch is busy. Any thread; pure.
 */
class SensorClaim {

    private val owner = AtomicReference<String?>(null)

    /** Who holds the sensor now, or null. */
    val holder: String?
        get() = owner.get()

    /** Takes the sensor for [who]; false when another holds it. Taking it again as its holder is true. */
    fun take(who: String): Boolean = owner.compareAndSet(null, who) || owner.get() == who

    /** Lets go of the sensor, if [who] holds it. */
    fun release(who: String) {
        owner.compareAndSet(who, null)
    }
}
