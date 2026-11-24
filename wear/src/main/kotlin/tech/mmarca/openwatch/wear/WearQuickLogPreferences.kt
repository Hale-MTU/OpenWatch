package tech.mmarca.openwatch.wear

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The quick log's entries the wearer chose, in their order, kept on the
 * watch and followed as they change ([chosen]); the quick log's editor
 * changes them, as the tile editor changes the dashboard's
 * ([WearTilePreferences]).
 *
 * Stored as [Choices] stores a choice: the [QuickLogItem] names joined by
 * commas, so no [QuickLogItem] is ever renamed. Nothing stored is
 * [QuickLogItem.Defaults], every item; an empty string is none.
 *
 * One per process ([WearGraph.quickLog]); build it off the main thread, since
 * the first read loads the file. A change is one write under a lock.
 */
internal class WearQuickLogPreferences(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private val lock = Any()
    private val flow = MutableStateFlow(load())

    /** The chosen entries now and after every change. */
    val chosen: StateFlow<List<QuickLogItem>> = flow.asStateFlow()

    /** Applies [change] to the chosen entries and keeps the result. Writes the file: not on the main thread. */
    fun edit(change: (List<QuickLogItem>) -> List<QuickLogItem>) = synchronized(lock) {
        val next = change(flow.value).distinct()
        prefs.edit { putString(KEY_ITEMS, Choices.encode(next) { it.name }) }
        flow.value = next
    }

    /** Back to the defaults: nothing stored, so the defaults of a later build apply too. */
    fun reset() = synchronized(lock) {
        prefs.edit { remove(KEY_ITEMS) }
        flow.value = QuickLogItem.Defaults
    }

    private fun load(): List<QuickLogItem> = Choices.decode(prefs.getString(KEY_ITEMS, null), QuickLogItem.Defaults, QuickLogItem::byName)

    companion object {
        /** Final once shipped: the file and its key are read by every later build. */
        const val PREFS = "wear_quicklog_prefs"
        const val KEY_ITEMS = "active_items"
    }
}
