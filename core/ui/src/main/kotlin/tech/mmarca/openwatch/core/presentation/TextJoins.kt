package tech.mmarca.openwatch.core.presentation

import android.icu.text.ListFormatter
import android.text.BidiFormatter
import java.text.NumberFormat
import java.util.Locale

/**
 * Pieces of text put together the way the phone's language does it, rather than with an
 * English comma or a percent sign glued on in code. In Arabic a list takes "،" and its own
 * "and", and a percentage its own digits and sign; in a right-to-left line a Latin name keeps
 * its place only if it is isolated.
 */

/** [items] as a sentence list: "A, B and C", in [locale]'s punctuation and conjunction. */
fun localizedList(items: List<String>, locale: Locale = Locale.getDefault()): String = when (items.size) {
    0 -> ""
    1 -> items.single()
    else -> ListFormatter.getInstance(locale).format(items)
}

/**
 * The parts of one status line, kept apart by [separator], each isolated in its own direction:
 * a watch named in Latin script inside an Arabic line, or the reverse, stays where it belongs.
 */
fun joinParts(parts: List<String>, separator: String = " · "): String {
    val bidi = BidiFormatter.getInstance()
    return parts.joinToString(separator) { bidi.unicodeWrap(it) }
}

/** [percent] (0 to 100) as [locale] writes a percentage. */
fun formatPercent(percent: Int, locale: Locale = Locale.getDefault()): String =
    NumberFormat.getPercentInstance(locale).format(percent / 100.0)
