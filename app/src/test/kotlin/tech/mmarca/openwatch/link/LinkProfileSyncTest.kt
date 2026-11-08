package tech.mmarca.openwatch.link

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openlink.OpenLinkProfile
import tech.mmarca.openlink.OpenLinkSex
import tech.mmarca.openwatch.data.repository.contract.FakeBodyProfilePreferences
import tech.mmarca.openwatch.devices.FakeSharedPreferences
import tech.mmarca.openwatch.domain.preferences.BiologicalSex
import tech.mmarca.openwatch.domain.preferences.BodyProfile

class LinkProfileSyncTest {

    private val context: Context = mockk {
        every { getSharedPreferences(any(), any()) } returns FakeSharedPreferences()
    }
    private val preferences = FakeBodyProfilePreferences(BodyProfile(birthYear = 1990, heightCm = 180.0))
    private val sync = LinkProfileSync(context, preferences)

    @Test
    fun `a newer pushed profile replaces this one, and its echo is not published back`() {
        val pushed = OpenLinkProfile(birthYear = 1985, sex = OpenLinkSex.FEMALE, heightCm = 168.0, weightKg = 61.0, updatedAtMillis = 2_000)

        assertTrue(sync.accept(pushed))

        val stored = preferences.bodyProfile()
        assertEquals(1985, stored.birthYear)
        assertEquals(BiologicalSex.FEMALE, stored.sex)
        // The profile flow shows the accepted profile; that is not a change made here.
        assertNull(sync.onLocalProfile(stored, now = 3_000))
        assertEquals(2_000, sync.current().updatedAtMillis)
        assertEquals(2_000, sync.updatedAtMillis.value)
    }

    @Test
    fun `an older pushed profile is ignored`() {
        sync.onLocalProfile(preferences.bodyProfile(), now = 1_000)
        sync.onLocalProfile(BodyProfile(birthYear = 1990, heightCm = 182.0), now = 5_000)

        assertFalse(sync.accept(OpenLinkProfile(birthYear = 1970, updatedAtMillis = 4_000)))

        assertEquals(1990, preferences.bodyProfile().birthYear)
    }

    @Test
    fun `the first profile seen predates the link, and an empty one never wins`() {
        // A real profile here is stamped 1: older than any edit OpenVitals makes, newer than its empty one.
        assertEquals(1L, sync.onLocalProfile(preferences.bodyProfile(), now = 9_000)!!.updatedAtMillis)
        assertFalse(sync.accept(OpenLinkProfile(updatedAtMillis = 0)))

        val emptySide = LinkProfileSync(
            mockk { every { getSharedPreferences(any(), any()) } returns FakeSharedPreferences() },
            FakeBodyProfilePreferences(BodyProfile()),
        )
        assertNull(emptySide.onLocalProfile(BodyProfile(), now = 9_000))
        assertTrue(emptySide.accept(OpenLinkProfile(birthYear = 1985, updatedAtMillis = 1)))
    }

    @Test
    fun `a change made here is stamped and published once`() {
        sync.onLocalProfile(preferences.bodyProfile(), now = 1_000)
        val edited = BodyProfile(birthYear = 1990, heightCm = 181.0)

        val published = sync.onLocalProfile(edited, now = 7_000)

        assertNotNull(published)
        assertEquals(181.0, published!!.heightCm!!, 0.0)
        assertEquals(7_000, published.updatedAtMillis)
        assertNull(sync.onLocalProfile(edited, now = 8_000))
    }
}
