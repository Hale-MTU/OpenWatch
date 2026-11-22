package tech.mmarca.openwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.crypto.KeyGenerator
import tech.mmarca.openwatch.wearlink.SecretSeal
import tech.mmarca.openwatch.wearlink.WearLinkToken

class WearTrustStoreTest {

    private val prefs = FakeSharedPreferences()
    private var now = 1_000_000L
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val seal = SecretSeal { key }
    private val linkState = WearLinkState()
    private val store = WearTrustStore(prefs, seal, linkState, clock = { now })
    private val pendings = ArrayList<PendingPhone>()
    private val phone = "AA:BB:CC:DD:EE:01"
    private val token = WearLinkToken.generate()

    @Before
    fun listen() {
        store.onPending = { pendings += it }
    }

    @Test
    fun `an unknown phone is pending once, then trusted with its token`() {
        store.notePending(phone, token, "Pixel")
        store.notePending(phone, token, "Pixel")

        assertEquals(1, pendings.size)
        assertEquals(PendingPhone(phone, token, "Pixel", now), linkState.state.value.pending)
        assertNull(store.tokenFor(phone))

        store.trust(phone, token, "Pixel")

        assertEquals(token, store.tokenFor(phone))
        assertNull(linkState.state.value.pending)
        assertEquals(listOf(TrustedPhone(phone, "Pixel", now)), store.trusted())
        assertEquals(store.trusted(), linkState.state.value.trusted)
    }

    @Test
    fun `another phone replaces the pending one, and an expired request is gone`() {
        store.notePending(phone, token, "Pixel")
        store.notePending("AA:BB:CC:DD:EE:02", WearLinkToken.generate(), "OnePlus")

        assertEquals("OnePlus", linkState.state.value.pending?.name)
        assertEquals(2, pendings.size)

        now += WearLinkState.PENDING_TTL_MILLIS + 1
        assertNull(linkState.pendingIfFresh(now))
        linkState.expirePending(now)
        assertNull(linkState.state.value.pending)
    }

    @Test
    fun `block drops the token, unblock and forget bring the phone back to unknown`() {
        store.trust(phone, token, "Pixel")
        store.block(phone)

        assertTrue(store.isBlocked(phone))
        assertNull(store.tokenFor(phone))
        assertTrue(store.trusted().isEmpty())

        store.forget(phone)

        assertFalse(store.isBlocked(phone))
        assertNull(store.tokenFor(phone))
    }

    @Test
    fun `a trusted phone with a new key is pending as such, and allowing it replaces the key`() {
        val newToken = WearLinkToken.generate()
        store.trust(phone, token, "Pixel")
        store.notePending(phone, newToken, "Pixel")

        assertEquals(PendingPhone(phone, newToken, "Pixel", now, newKey = true), linkState.state.value.pending)
        assertEquals(token, store.tokenFor(phone))

        store.trust(phone, newToken, "Pixel")

        assertEquals(newToken, store.tokenFor(phone))
        assertNull(linkState.state.value.pending)
        assertEquals(1, store.trusted().size)
    }

    @Test
    fun `addresses are matched regardless of case and survive a restart`() {
        store.trust(phone.lowercase(), token, "Pixel")

        assertEquals(token, WearTrustStore(prefs, seal, WearLinkState()).tokenFor(phone))
    }

    @Test
    fun `a token is kept sealed, and one kept in plain before is kept and sealed`() {
        store.trust(phone, token, "Pixel")
        val stored = prefs.getString("token_$phone", null)!!
        assertTrue(stored.startsWith(SecretSeal.PREFIX))
        assertFalse(stored.contains(token))

        val other = "AA:BB:CC:DD:EE:02"
        val older = WearLinkToken.generate()
        prefs.edit().putString("token_$other", older).apply()

        assertEquals(older, store.tokenFor(other))
        assertTrue(prefs.getString("token_$other", null)!!.startsWith(SecretSeal.PREFIX))
    }

    @Test
    fun `a token this watch can no longer open asks the wearer again`() {
        store.trust(phone, token, "Pixel")
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        assertNull(WearTrustStore(prefs, SecretSeal { otherKey }, WearLinkState()).tokenFor(phone))
    }
}
