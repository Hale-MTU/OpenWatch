package tech.mmarca.openwatch.wearlink

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A sealed secret opens only under its own key, and a secret from before sealing still reads. */
class SecretSealTest {

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val key = newKey()
    private val seal = SecretSeal { key }

    @Test
    fun `a sealed secret opens to itself and does not show it`() {
        val token = WearLinkToken.generate()

        val stored = seal.seal(token)!!

        assertTrue(seal.isSealed(stored))
        assertFalse(stored.contains(token))
        assertEquals(token, seal.open(stored))
        // A fresh nonce each time: the same secret never stores the same way twice.
        assertNotEquals(stored, seal.seal(token))
    }

    @Test
    fun `another device's key, an altered value or a broken one opens to nothing`() {
        val stored = seal.seal("0123456789abcdef0123456789abcdef")!!
        val altered = stored.dropLast(2) + if (stored.endsWith("AA")) "BA" else "AA"

        assertNull(SecretSeal { newKey() }.open(stored))
        assertNull(seal.open(altered))
        assertNull(seal.open(SecretSeal.PREFIX + "not base64!"))
        assertNull(seal.open(SecretSeal.PREFIX + "AAAA"))
    }

    @Test
    fun `a secret kept in plain before sealing reads as it is`() {
        assertFalse(seal.isSealed("0123456789abcdef"))
        assertEquals("0123456789abcdef", seal.open("0123456789abcdef"))
    }

    @Test
    fun `without a key nothing is sealed, and a sealed value stays shut`() {
        val stored = seal.seal("secret")!!
        val keyless = SecretSeal { null }

        assertNull(keyless.seal("secret"))
        assertNull(keyless.open(stored))
    }
}
