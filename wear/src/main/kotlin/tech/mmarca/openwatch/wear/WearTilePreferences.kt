package tech.mmarca.openwatch.wear

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The dashboard's tiles the wearer chose, in their order, kept on the watch
 * and followed as they change ([chosen]). The tile editor changes them; the
 * dashboard shows those of them the watch offers now, so a chosen tile that
 * is not on offer (a night's vital before its first reading) stays chosen
 * and appears once it is.
 *
 * Stored as [Choices] stores a choice: the [MetricTile] names joined by
 * commas, so no [MetricTile] is ever renamed. Nothing stored is
 * [MetricTile.DefaultTiles]; an empty string is no tile at all.
 *
 * One per process ([WearGraph.tiles]); build it off the main thread, since
 * the first read loads the file. A change is one write under a lock, so two
 * quick taps both count.
 */
internal class WearTilePreferences(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private val lock = Any()
    private val flow = MutableStateFlow(load())

    /** The chosen tiles now and after every change. */
    val chosen: StateFlow<List<MetricTile>> = flow.asStateFlow()

    /** Applies [change] to the chosen tiles and keeps the result. Writes the file: not on the main thread. */
    fun edit(change: (List<MetricTile>) -> List<MetricTile>) = synchronized(lock) {
        val next = change(flow.value).distinct()
        prefs.edit { putString(KEY_TILES, Choices.encode(next) { it.name }) }
        flow.value = next
    }

    /** Back to the defaults: nothing stored, so the defaults of a later build apply too. */
    fun reset() = synchronized(lock) {
        prefs.edit { remove(KEY_TILES) }
        flow.value = MetricTile.DefaultTiles
    }

    private fun load(): List<MetricTile> =
        Choices.decode(prefs.getString(KEY_TILES, null), MetricTile.DefaultTiles) { name ->
            MetricTile.entries.firstOrNull { it.name == name }
        }

    companion object {
        /** Final once shipped: the file and its key are read by every later build. */
        const val PREFS = "wear_tile_prefs"
        const val KEY_TILES = "active_tiles"
    }
}
