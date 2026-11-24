package tech.mmarca.openwatch.wearlink

/**
 * The one pull for every series the watch keeps one line per value (heart rate,
 * sleep minutes, steps, ascent, HRV, breathing, SpO2, skin temperature, the
 * entries made on the watch):
 * `SINCE <key> <cursor> <limit>`, served straight from the watch's store by
 * sequence number. Every row there is numbered when it is inserted and again
 * when it is replaced, so a row rewritten in place or written late comes again,
 * after the rows sent before it.
 *
 * - `<key>` is a series the watch announces in its hello reply (`hr`, `sm`,
 *   `st`, `as`, `hv`, `rr`, `sk`, `o2`, `lg`): the key of its rows.
 * - `<cursor>` is one of two positions:
 *   - `<store>.<seq>`, the cursor the watch's last reply for that key ended
 *     with: its store's id and the sequence number of the last row it sent.
 *     The phone keeps it as it came. A cursor of another store (another
 *     watch, or this one after its data was cleared) starts from the first
 *     row the watch keeps.
 *   - `t<epochMillis>`, a time position ([afterTime]): from the first row, in
 *     sequence order, newer than that time. It hands a time cursor over to
 *     the sequence: every row newer than the time comes, and so does every
 *     row written after the first of those, whatever its time. `t0` is
 *     everything the watch keeps.
 * - `<limit>` is at most [MAX_LIMIT]; the watch clamps it.
 *
 * The reply is up to `limit` of the series' own lines (`HR ...`, `SM ...`),
 * each as the watch stored it, in sequence order, then
 * `END <count> <more> <cursor>`: the lines sent, `1` when the limit cut the
 * reply short, and the `<store>.<seq>` cursor to ask with next (after the last
 * row the watch looked at, so it moves past a row too broken to send). A key
 * the watch does not serve is `ERROR bad_request`.
 */
object WearLinkSince {

    const val WORD: String = "SINCE"

    /** The most lines one reply carries. The phone pages past it. */
    const val MAX_LIMIT: Int = 2000

    /** A cursor longer than this is not one the watch gave. */
    const val MAX_CURSOR_LENGTH: Int = 40

    /** One reply: the lines, whether the limit cut it short, and the cursor to ask with next. */
    data class Page(val lines: List<String>, val more: Boolean, val cursor: String)

    /** The reply's last line. */
    data class End(val count: Int, val more: Boolean, val cursor: String)

    /** Where a cursor points, as the watch reads it. */
    sealed class Position {
        /** After the row numbered [seq] in this store. */
        data class AfterSeq(val seq: Long) : Position()

        /** From the first row, in sequence order, newer than [epochMillis]. */
        data class AfterTime(val epochMillis: Long) : Position()
    }

    /** The time position for [epochMillis]: the hand-over from a time cursor. */
    fun afterTime(epochMillis: Long): String = "$TIME_PREFIX${epochMillis.coerceAtLeast(0)}"

    /** The watch's cursor after the row numbered [seq] in the store [storeId]. */
    fun cursor(storeId: String, seq: Long): String = "$storeId.$seq"

    /**
     * Where [cursor] points in the store [storeId]. A cursor of another store, or one the
     * watch did not write, points before the first row: a re-pull of what the watch keeps,
     * never a gap.
     */
    fun position(cursor: String, storeId: String): Position {
        if (cursor.startsWith(TIME_PREFIX)) {
            cursor.substring(TIME_PREFIX.length).toLongOrNull()?.takeIf { it >= 0 }?.let { return Position.AfterTime(it) }
            return Position.AfterSeq(0L)
        }
        val dot = cursor.lastIndexOf('.')
        if (dot <= 0 || cursor.substring(0, dot) != storeId) return Position.AfterSeq(0L)
        return Position.AfterSeq(cursor.substring(dot + 1).toLongOrNull()?.takeIf { it >= 0 } ?: 0L)
    }

    fun formatRequest(request: WearLinkProtocol.Request.SeriesSince): String =
        "$WORD ${request.key} ${request.cursor} ${request.limit.coerceIn(1, MAX_LIMIT)}"

    /** Null unless [line] is a well-formed `SINCE` request. The limit is clamped. */
    fun parseRequest(line: String): WearLinkProtocol.Request.SeriesSince? {
        val fields = fields(line)
        if (fields.size != 4 || fields[0] != WORD || !isKey(fields[1]) || !isCursor(fields[2])) return null
        val limit = fields[3].toIntOrNull()?.takeIf { it >= 1 } ?: return null
        return WearLinkProtocol.Request.SeriesSince(fields[1], fields[2], limit.coerceAtMost(MAX_LIMIT))
    }

    fun formatEnd(count: Int, more: Boolean, cursor: String): String = "${WearLinkProtocol.formatEnd(count, more)} $cursor"

    /** Null unless [line] is a well-formed `END` with a cursor. */
    fun parseEnd(line: String): End? {
        val end = WearLinkProtocol.parseEnd(line) ?: return null
        val cursor = fields(line).getOrNull(3)?.takeIf(::isCursor) ?: return null
        return End(end.count, end.more, cursor)
    }

    /** A stored line goes out only as one whole line within the limit; any other is skipped, never sent broken. */
    fun isSendable(line: String): Boolean =
        line.isNotBlank() && line.none { it == '\n' || it == '\r' } &&
            line.toByteArray(Charsets.UTF_8).size <= WearLinkProtocol.MAX_LINE_LENGTH

    private fun isKey(token: String): Boolean = token.length in 1..MAX_KEY_LENGTH && token.all { it.isAsciiAlphanumeric() || it == '_' }

    private fun isCursor(token: String): Boolean =
        token.length in 1..MAX_CURSOR_LENGTH && token.all { it.isAsciiAlphanumeric() || it == '.' || it == '_' || it == '-' }

    private fun Char.isAsciiAlphanumeric(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private fun fields(line: String): List<String> = line.trim().split(' ').filter { it.isNotEmpty() }

    private const val TIME_PREFIX = "t"
    private const val MAX_KEY_LENGTH = 16
}
