package tech.mmarca.openwatch.wearlink

import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A secret at rest, sealed with AES-GCM under a key the platform keeps. On a phone or a watch
 * that is the Android Keystore, which never hands the key out and does not move it to another
 * device, so a copied preferences file opens nowhere else. Both apps keep their link tokens
 * this way, and the phone its scale key.
 *
 * Stored as `v1:` and the Base64 of the 12-byte nonce followed by the ciphertext and its tag.
 * Any other text is a secret a store kept in plain before sealing: [open] reads it as it is,
 * and [isSealed] tells the store to write it back sealed.
 *
 * [key] is null when the platform cannot give one. Then [seal] returns null and the store
 * keeps the plain text, as before, rather than lose a pairing; a later write seals it.
 */
class SecretSeal(private val key: () -> SecretKey?) {

    /** [secret] sealed for storage, or null when no key is to be had. */
    fun seal(secret: String): String? {
        val key = key() ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            // The cipher picks the nonce: the Keystore refuses one chosen by the caller.
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val nonce = cipher.iv
            if (nonce.size != NONCE_BYTES) return null
            PREFIX + Base64.getEncoder().encodeToString(nonce + cipher.doFinal(secret.toByteArray(Charsets.UTF_8)))
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    /**
     * The secret [stored] holds: the plain text itself when it was never sealed, and null when
     * it is sealed under a key this device no longer has, or was altered.
     */
    fun open(stored: String): String? {
        if (!isSealed(stored)) return stored
        val bytes = try {
            Base64.getDecoder().decode(stored.substring(PREFIX.length))
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.size <= NONCE_BYTES + TAG_BYTES) return null
        val key = key() ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, bytes, 0, NONCE_BYTES))
            String(cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    fun isSealed(stored: String): Boolean = stored.startsWith(PREFIX)

    companion object {
        const val PREFIX: String = "v1:"
        const val TRANSFORMATION: String = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BYTES = 16
    }
}
