package tech.mmarca.openwatch.wear

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.mmarca.openwatch.wearlink.DaySummary
import tech.mmarca.openwatch.wearlink.WearLinkDaily

/**
 * The phone's figures per day (`PUT_DAY`, [WearLinkDaily]): the night, the
 * resting heart rate, the active energy and the distance, which the watch
 * cannot work out by itself. The phone pushes the last days after each sync;
 * the watch keeps the [KEEP_DAYS] newest, each as the line it came as, in a
 * preferences file of their own, and shows them on the sleep, resting heart
 * rate, active calories and distance tiles ([WearReadings]).
 *
 * One per process ([WearGraph.phoneDays]); the first read loads the file, so
 * build it off the main thread. [days] follows every day put.
 */
class PhoneDays(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private val lock = Any()
    private val flow = MutableStateFlow(load())

    /** The kept days by their day number, now and after every day put. */
    val days: StateFlow<Map<Long, DaySummary>> = flow.asStateFlow()

    /** Keeps [day], replacing what the phone sent for it before, and drops all but the newest [KEEP_DAYS]. Any thread. */
    fun put(day: DaySummary): Boolean = synchronized(lock) {
        val next = (flow.value + (day.epochDay to day)).entries.sortedByDescending { it.key }.take(KEEP_DAYS).associate { it.key to it.value }
        prefs.edit {
            for (gone in flow.value.keys - next.keys) remove(key(gone))
            if (day.epochDay in next) putString(key(day.epochDay), WearLinkDaily.format(day))
        }
        flow.value = next
        true
    }

    private fun load(): Map<Long, DaySummary> =
        prefs.all.entries
            .filter { it.key.startsWith(KEY_PREFIX) }
            .mapNotNull { (it.value as? String)?.let(WearLinkDaily::parse) }
            .associateBy { it.epochDay }

    companion object {
        /** Final once shipped: the file and its keys are read by every later build. */
        const val PREFS = "wear_day_summaries"
        const val KEY_PREFIX = "day_"

        /** Two weeks: the week the screens show, and room for a phone that syncs seldom. */
        const val KEEP_DAYS = 14

        fun key(epochDay: Long): String = "$KEY_PREFIX$epochDay"
    }
}
