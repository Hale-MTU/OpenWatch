package tech.mmarca.openwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the watch remembers of a pull. When it warns is `SyncRetentionTest`, in `:wear:recording-core`. */
class PhonePullsTest {

    private val pulled = 1_700_000_000_000L

    @Test
    fun `the last pull and the reminder shown survive a restart`() {
        val prefs = FakeSharedPreferences()
        assertNull(PhonePulls(prefs).lastPulledMillis)

        PhonePulls(prefs).apply {
            notePulled(pulled)
            reminded = "$pulled SOON"
        }

        val restarted = PhonePulls(prefs)
        assertEquals(pulled, restarted.lastPulledMillis)
        assertEquals("$pulled SOON", restarted.reminded)
        assertEquals(pulled, prefs.getLong(PhonePulls.KEY_LAST_PULLED, 0))
    }
}
