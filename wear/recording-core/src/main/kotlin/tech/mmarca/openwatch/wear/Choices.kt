package tech.mmarca.openwatch.wear

/**
 * An ordered choice the wearer makes from a fixed set of items, as the
 * editors keep it: the dashboard's tiles, in the order they are shown. It is
 * stored as the items' names joined by commas ([encode]), so an item's name
 * is never changed once shipped. Nothing stored means the defaults, an empty
 * string means none. Pure, so the edits are tested without a watch.
 */
object Choices {

    private const val SEPARATOR = ","

    /** [items] as they are stored: their names, in order, joined by commas. */
    fun <T> encode(items: List<T>, name: (T) -> String): String = items.joinToString(SEPARATOR, transform = name)

    /**
     * A stored choice read back. Null (nothing stored) is [defaults]; an empty
     * string is none. A name this build has no item for ([byName] answers
     * null), as a later build may have stored, and a second copy of a name are
     * dropped.
     */
    fun <T> decode(raw: String?, defaults: List<T>, byName: (String) -> T?): List<T> {
        if (raw == null) return defaults
        return raw.split(SEPARATOR).mapNotNull { byName(it.trim()) }.distinct()
    }

    /** [chosen] with [item] switched on, at the end, or off. */
    fun <T> switched(chosen: List<T>, item: T, on: Boolean): List<T> = when {
        !on -> chosen - item
        item in chosen -> chosen
        else -> chosen + item
    }

    /**
     * [chosen] with [item] moved one place [up], toward the start, or down,
     * among the items [shown]: it trades places with its shown neighbour. A
     * chosen item that is not shown (a metric this watch does not offer now)
     * keeps its place, so the move is the one the wearer saw, and a move down
     * undoes a move up. Unchanged at either end, and for an item not shown.
     */
    fun <T> moved(chosen: List<T>, item: T, up: Boolean, shown: (T) -> Boolean): List<T> {
        val visible = chosen.filter(shown)
        val at = visible.indexOf(item)
        if (at < 0) return chosen
        val neighbour = visible.getOrNull(if (up) at - 1 else at + 1) ?: return chosen
        return chosen.map { other ->
            when (other) {
                item -> neighbour
                neighbour -> item
                else -> other
            }
        }
    }
}
