package tech.mmarca.openwatch.wear

import tech.mmarca.openwatch.wearlink.WearLinkEntries
import tech.mmarca.openwatch.wearlink.WearLinkEntry

/**
 * The entries made on the watch ([WearMetrics.ENTRIES]): what the wearer logs
 * by hand in the quick log, and what a spot measurement measures. The one
 * writer of the series, built once in [WearGraph]; the screens log through
 * it and never open the store, and read the entries back through
 * [WearReadingsSource], which follows the store's [MetricStore.updates]. The
 * phone pulls the series with `SINCE lg`.
 *
 * The store keys a row on its metric and time and keeps the first value at a
 * time ([WearMetric.OnSameTime.KEEP]), silently: a second glass of water in
 * the same millisecond would be lost. So an entry goes to the first free
 * millisecond from its own, under a lock, since the check and the write must
 * not interleave with another entry's. Entries older than the series'
 * retention are pruned at each write. Each entry written is told to
 * [onLogged], outside the lock: the graph asks the quick log tile to show a
 * glass of water logged in the app.
 */
internal class EntryLog(
    private val store: MetricStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val onLogged: (WearLinkEntry.Type) -> Unit = {},
) {
    private val lock = Any()

    /**
     * Logs [value] (and [second]) of [type], in the type's unit, at [atMillis]
     * or the first free millisecond after it; [measured] for a value a sensor
     * of the watch took. Writes the store: not on the main thread. False when
     * the entry is not one the link can carry, or could not be written.
     */
    fun log(
        type: WearLinkEntry.Type,
        value: Double,
        second: Double? = null,
        measured: Boolean = false,
        atMillis: Long = now(),
    ): Boolean {
        val entry = WearLinkEntry.of(atMillis, type, value, second, measured)
        // Never a row the phone could not read: what is written is what the line parses back to.
        if (WearLinkEntries.parse(WearLinkEntries.format(entry)) != entry) return false
        val written = synchronized(lock) {
            val taken = store.timesTaken(WearMetrics.ENTRIES, atMillis, atMillis + MAX_SHIFT_MILLIS)
            val free = (atMillis until atMillis + MAX_SHIFT_MILLIS).firstOrNull { it !in taken } ?: return@synchronized false
            store.put(WearMetrics.ENTRIES, entry.copy(epochMillis = free)).also { written ->
                if (written) store.prune(WearMetrics.ENTRIES, now())
            }
        }
        if (written) onLogged(type)
        return written
    }

    private companion object {
        /** How far an entry may move to find a free millisecond: far more than any wearer taps in a second. */
        const val MAX_SHIFT_MILLIS = 1_000L
    }
}
