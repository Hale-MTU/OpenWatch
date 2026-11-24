package tech.mmarca.openwatch.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.devices.FakeSharedPreferences

/** The key names are load-bearing: the migrator copies the Flutter values under exactly these names. */
class WatchNotificationPrefsStoreTest {

    private val prefs = FakeSharedPreferences()

    private fun store() = WatchNotificationPrefsStore(prefs)

    @Test
    fun `everything defaults to off and empty`() {
        val store = store()
        assertFalse(store.enabled)
        assertTrue(store.blockedPackages.isEmpty())
        assertFalse(store.disclosureAccepted)
    }

    @Test
    fun `enabled round-trips through storage`() {
        store().enabled = true
        // A second store over the same prefs is the real round-trip.
        assertTrue(store().enabled)
    }

    @Test
    fun `the blocklist round-trips as a set`() {
        store().blockedPackages = setOf("com.spam.app", "com.other.app")
        assertEquals(setOf("com.spam.app", "com.other.app"), store().blockedPackages)
    }

    @Test
    fun `setBlocked adds and removes one package`() {
        val store = store()
        store.setBlocked("com.spam.app", blocked = true)
        store.setBlocked("com.other.app", blocked = true)
        assertEquals(setOf("com.spam.app", "com.other.app"), store.blockedPackages)

        store.setBlocked("com.spam.app", blocked = false)
        assertEquals(setOf("com.other.app"), store.blockedPackages)
    }

    @Test
    fun `disclosure acceptance is remembered independently of enabled`() {
        // Consent is given once; switching off and on must not re-prompt.
        val store = store()
        store.disclosureAccepted = true
        store.enabled = true
        store.enabled = false
        assertTrue(store().disclosureAccepted)
    }

    @Test
    fun `the exact Flutter key names are what lands on disk`() {
        val store = store()
        store.enabled = true
        store.blockedPackages = setOf("com.spam.app")
        store.disclosureAccepted = true

        assertTrue(prefs.getBoolean("garmin_notifications_enabled", false))
        assertEquals(
            setOf("com.spam.app"),
            prefs.getStringSet("garmin_notifications_blocked_packages", null),
        )
        assertTrue(prefs.getBoolean("garmin_notifications_disclosure_accepted", false))
    }

    // Adoption of phase-5 migrated values.

    private fun legacyMain(): FakeSharedPreferences = FakeSharedPreferences().apply {
        edit()
            .putBoolean("garmin_notifications_enabled", true)
            .putStringSet("garmin_notifications_blocked_packages", setOf("com.spam.app"))
            .putBoolean("garmin_notifications_disclosure_accepted", true)
            .apply()
    }

    @Test
    fun `values the migrator left in the main prefs file are adopted on first construction`() {
        val store = WatchNotificationPrefsStore(prefs, legacyMainPrefs = legacyMain())

        assertTrue(store.enabled)
        assertEquals(setOf("com.spam.app"), store.blockedPackages)
        assertTrue(store.disclosureAccepted)
        // And they were copied into the store's own file, not read through.
        assertTrue(store().enabled)
    }

    @Test
    fun `a value already in the store's own file wins over the migrated copy`() {
        prefs.edit().putBoolean("garmin_notifications_enabled", false).apply()

        val store = WatchNotificationPrefsStore(prefs, legacyMainPrefs = legacyMain())

        assertFalse(store.enabled)
        // The other keys, absent from the own file, are still adopted.
        assertTrue(store.disclosureAccepted)
    }

    @Test
    fun `the adoption never writes to the main prefs file`() {
        val main = legacyMain()
        WatchNotificationPrefsStore(prefs, legacyMainPrefs = main).enabled = false

        // The migrated copies are untouched; this store owns its own file.
        assertTrue(main.getBoolean("garmin_notifications_enabled", false))
    }

    @Test
    fun `an empty main prefs file adopts nothing`() {
        val store = WatchNotificationPrefsStore(prefs, legacyMainPrefs = FakeSharedPreferences())
        assertFalse(store.enabled)
        assertTrue(store.blockedPackages.isEmpty())
    }

    private fun listenerCopy(): FakeSharedPreferences = FakeSharedPreferences().apply {
        edit()
            .putBoolean("forwarding_enabled", true)
            .putStringSet("blocked_packages", setOf("com.bank.app"))
            .putString("watch_address", "AA:BB:CC:DD:EE:FF")
            .apply()
    }

    @Test
    fun `the listener's own copy is adopted where the store has none, then dropped`() {
        val listener = listenerCopy()

        val store = WatchNotificationPrefsStore(prefs, listenerPrefs = listener)

        assertTrue(store.enabled)
        assertEquals(setOf("com.bank.app"), store.blockedPackages)
        // Gone from the listener's file, so the two can never drift again.
        assertFalse(listener.contains("forwarding_enabled"))
        assertFalse(listener.contains("blocked_packages"))
        assertFalse(listener.contains("watch_address"))
    }

    @Test
    fun `the store's own setting wins over the listener's copy, which the screen kept in step`() {
        prefs.edit().putStringSet("garmin_notifications_blocked_packages", setOf("com.spam.app")).apply()

        val store = WatchNotificationPrefsStore(prefs, listenerPrefs = listenerCopy())

        assertEquals(setOf("com.spam.app"), store.blockedPackages)
        // The switch the store had no value for is still taken from the copy.
        assertTrue(store.enabled)
    }

    @Test
    fun `the two modes keep their own lists, so switching back loses neither`() {
        val store = store()
        store.setSends("com.spam.app", sends = false)
        assertTrue(!store.sends("com.spam.app") && store.sends("com.chat.app"))

        store.onlyAllowed = true
        store.setSends("com.chat.app", sends = true)
        assertTrue(store.sends("com.chat.app") && !store.sends("com.other.app"))

        store.onlyAllowed = false
        assertEquals(setOf("com.spam.app"), store.blockedPackages)
        assertEquals(setOf("com.chat.app"), store.allowedPackages)
    }

    @Test
    fun `sensitive content is hidden unless the user says otherwise, and every app sends`() {
        val store = store()
        assertTrue(store.hideSensitiveContent)
        assertFalse(store.onlyAllowed)
    }
}
