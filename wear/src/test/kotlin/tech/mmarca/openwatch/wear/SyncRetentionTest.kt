package tech.mmarca.openwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When the watch warns that rows no phone pulled are about to go, and what it remembers of a pull. */
class SyncRetentionTest {

    private val day = SyncRetention.DAY_MILLIS
    private val pulled = 1_700_000_000_000L

    @Test
    fun `no warning in the first five days after a pull, nor for a watch no phone was ever allowed on`() {
        assertNull(SyncRetention.warning(pulled + 5 * day - 1, lastPulledMillis = pulled, firstTrustedMillis = null))
        assertNull(SyncRetention.warning(pulled + 30 * day, lastPulledMillis = null, firstTrustedMillis = null))
    }

    @Test
    fun `from the fifth day the warning counts the days left, from the seventh the rows are going`() {
        val soon = SyncRetention.warning(pulled + 5 * day, pulled, null)!!
        assertEquals(SyncRetention.Level.SOON, soon.level)
        assertEquals(2, soon.daysLeft(pulled + 5 * day))
        assertEquals(1, soon.daysLeft(pulled + 6 * day + 1))
        assertEquals(pulled + WearMetric.WEEK_MILLIS, soon.pruneFromMillis)

        assertEquals(SyncRetention.Level.PRUNING, SyncRetention.warning(pulled + 7 * day, pulled, null)?.level)
    }

    @Test
    fun `a watch no phone pulled from counts from the first phone the wearer allowed`() {
        val trusted = pulled
        assertNull(SyncRetention.warning(trusted + 4 * day, lastPulledMillis = null, firstTrustedMillis = trusted))
        assertEquals(SyncRetention.Level.SOON, SyncRetention.warning(trusted + 5 * day, null, trusted)?.level)
        // A pull counts over the phone's allowing.
        assertNull(SyncRetention.warning(trusted + 5 * day, lastPulledMillis = trusted + 4 * day, firstTrustedMillis = trusted))
    }

    @Test
    fun `a reminder shows once per pull and level, and again for the next level or after a new pull`() {
        val soon = SyncRetention.warning(pulled + 5 * day, pulled, null)!!
        assertEquals(soon, SyncRetention.reminderToShow(soon, reminded = null))
        assertNull(SyncRetention.reminderToShow(soon, reminded = soon.key))

        val pruning = SyncRetention.warning(pulled + 7 * day, pulled, null)!!
        assertEquals(pruning, SyncRetention.reminderToShow(pruning, reminded = soon.key))

        val later = SyncRetention.warning(pulled + 13 * day, pulled + 8 * day, null)!!
        assertEquals(later, SyncRetention.reminderToShow(later, reminded = pruning.key))
    }

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
