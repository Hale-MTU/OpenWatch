package tech.mmarca.openwatch.core.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import tech.mmarca.openwatch.wearlink.SecretSeal

/**
 * The seal against the real Keystore, which the unit tests stand in for with a software key:
 * the key is made once, found again by a new provider (as after a restart), and opens only
 * what it sealed. Run on a Pixel 6 Pro, Android 17, 2026-11-22.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretKeyTest {

    private val alias = "openwatch_secrets_test_${System.nanoTime()}"

    @After
    fun deleteKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun aSealedSecretOpensAfterARestartAndUnderNoOtherKey() {
        val stored = SecretSeal(KeystoreSecretKey(alias)).seal("0728974d657a4b60964c1b1677f35f7c")
        assertNotNull(stored)

        assertEquals("0728974d657a4b60964c1b1677f35f7c", SecretSeal(KeystoreSecretKey(alias)).open(stored!!))
        assertNull(SecretSeal(KeystoreSecretKey("${alias}_other")).open(stored))
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("${alias}_other")
    }
}
