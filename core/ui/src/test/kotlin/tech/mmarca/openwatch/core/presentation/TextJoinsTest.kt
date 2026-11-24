package tech.mmarca.openwatch.core.presentation

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Lists and percentages in the phone's language, not in English punctuation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextJoinsTest {

    @Test
    fun `a list takes the language's own comma and conjunction`() {
        assertEquals("", localizedList(emptyList()))
        assertEquals("Venu", localizedList(listOf("Venu")))
        assertEquals("Venu and Pixel", localizedList(listOf("Venu", "Pixel"), Locale.ENGLISH))
        // Arabic: its own conjunction, never an English "and".
        val arabic = localizedList(listOf("Venu", "Pixel", "S400"), Locale.forLanguageTag("ar"))
        assertTrue(arabic, "and" !in arabic && arabic.contains("و"))
    }

    @Test
    fun `a percentage is written as the locale writes one`() {
        assertEquals("80%", formatPercent(80, Locale.ENGLISH))
        assertEquals("80 %", formatPercent(80, Locale.GERMAN).replace(' ', ' '))
    }

    @Test
    fun `each part of a line is isolated in its own direction`() {
        val line = joinParts(listOf("Venu 3", "٨٠٪"))
        assertTrue(line.contains(" · "))
        assertTrue(line.startsWith("⁨") || line.startsWith("‪") || line.startsWith("Venu"))
    }
}
