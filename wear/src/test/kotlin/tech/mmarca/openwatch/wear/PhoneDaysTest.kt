package tech.mmarca.openwatch.wear

import androidx.core.content.edit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openwatch.wearlink.DaySummary
import tech.mmarca.openwatch.wearlink.WearLinkDaily

class PhoneDaysTest {

    @Test
    fun `a day the phone put survives a restart, as the line it came as, and replaces the one before`() {
        val prefs = FakeSharedPreferences()
        PhoneDays(prefs).apply {
            put(DaySummary(20_400, sleepMinutes = 400))
            put(DaySummary(20_400, sleepMinutes = 410, restingBpm = 55))
        }

        val restarted = PhoneDays(prefs)
        assertEquals(mapOf(20_400L to DaySummary(20_400, sleepMinutes = 410, restingBpm = 55)), restarted.days.value)
        assertEquals("PUT_DAY 20400 410 - - - - 55 - -", prefs.getString(PhoneDays.key(20_400), null))
    }

    @Test
    fun `only the newest fourteen days are kept, and an older day put late is dropped`() {
        val prefs = FakeSharedPreferences()
        val days = PhoneDays(prefs)
        for (day in 20_400L..20_415L) days.put(DaySummary(day, activeKcal = 300))
        days.put(DaySummary(20_390, activeKcal = 100))

        assertEquals((20_402L..20_415L).toSet(), days.days.value.keys)
        assertNull(prefs.getString(PhoneDays.key(20_401), null))
        assertNull(prefs.getString(PhoneDays.key(20_390), null))
        assertEquals(14, PhoneDays(prefs).days.value.size)
    }

    @Test
    fun `a stored line this build cannot read is left out`() {
        val prefs = FakeSharedPreferences()
        prefs.edit {
            putString(PhoneDays.key(20_400), "PUT_DAY 20400 9999 - - - - - - -")
            putString(PhoneDays.key(20_401), WearLinkDaily.format(DaySummary(20_401, distanceMetres = 4_000)))
        }

        assertEquals(setOf(20_401L), PhoneDays(prefs).days.value.keys)
    }
}
