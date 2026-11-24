package tech.mmarca.openwatch.wear

import androidx.core.content.edit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openwatch.wear.QuickLogItem.BLOOD_PRESSURE
import tech.mmarca.openwatch.wear.QuickLogItem.BREATHE
import tech.mmarca.openwatch.wear.QuickLogItem.HEIGHT
import tech.mmarca.openwatch.wear.QuickLogItem.WATER
import tech.mmarca.openwatch.wear.QuickLogItem.WEIGHT

/** The quick log's chosen entries, as stored. How a choice is edited is `ChoicesTest`, in `:wear:recording-core`. */
class WearQuickLogPreferencesTest {

    private val prefs = FakeSharedPreferences()

    @Test
    fun `nothing chosen yet is every entry, in the order the quick log always listed them`() {
        assertEquals(QuickLogItem.Defaults, WearQuickLogPreferences(prefs).chosen.value)
        assertEquals(
            listOf("WATER", "WEIGHT", "BLOOD_PRESSURE", "BODY_FAT", "BLOOD_OXYGEN", "RESPIRATORY_RATE", "BODY_TEMPERATURE", "BLOOD_GLUCOSE", "HEIGHT", "BREATHE"),
            QuickLogItem.entries.map { it.name },
        )
    }

    @Test
    fun `a choice survives a restart, stored as the entries' names, and none stays none`() {
        WearQuickLogPreferences(prefs).edit { listOf(BREATHE, WATER, HEIGHT) }

        assertEquals(listOf(BREATHE, WATER, HEIGHT), WearQuickLogPreferences(prefs).chosen.value)
        assertEquals("BREATHE,WATER,HEIGHT", prefs.getString(WearQuickLogPreferences.KEY_ITEMS, null))

        WearQuickLogPreferences(prefs).edit { emptyList() }
        assertEquals(emptyList<QuickLogItem>(), WearQuickLogPreferences(prefs).chosen.value)
    }

    @Test
    fun `each change starts from the last one, and a reset forgets the choice`() {
        val items = WearQuickLogPreferences(prefs)
        items.edit { listOf(WATER, WEIGHT, BLOOD_PRESSURE) }
        items.edit { Choices.moved(it, BLOOD_PRESSURE, up = true) { true } }
        items.edit { Choices.switched(it, WATER, on = false) }

        assertEquals(listOf(BLOOD_PRESSURE, WEIGHT), items.chosen.value)

        items.reset()
        assertEquals(QuickLogItem.Defaults, items.chosen.value)
        assertNull(prefs.getString(WearQuickLogPreferences.KEY_ITEMS, null))
    }

    @Test
    fun `a name this build does not know is dropped`() {
        prefs.edit { putString(WearQuickLogPreferences.KEY_ITEMS, "WEIGHT,COFFEE,WATER,WEIGHT") }

        assertEquals(listOf(WEIGHT, WATER), WearQuickLogPreferences(prefs).chosen.value)
    }
}
