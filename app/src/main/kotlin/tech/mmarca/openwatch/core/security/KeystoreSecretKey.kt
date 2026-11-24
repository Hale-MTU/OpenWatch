package tech.mmarca.openwatch.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import tech.mmarca.openwatch.wearlink.SecretSeal

/**
 * The phone's key for [SecretSeal], made in the Android Keystore on first use under [alias]
 * and kept there: it never leaves the Keystore, so a copied preferences file opens nowhere
 * else. Null when the Keystore fails, and asked again next time.
 */
class KeystoreSecretKey(private val alias: String = ALIAS) : () -> SecretKey? {

    @Volatile
    private var key: SecretKey? = null

    override fun invoke(): SecretKey? = key ?: synchronized(this) {
        key ?: runCatching { loadOrCreate() }.getOrNull()?.also { key = it }
    }

    private fun loadOrCreate(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"

        /** Persisted: a new alias is a new key, and every sealed secret would stop opening. */
        const val ALIAS = "openwatch_secrets_v1"
        const val KEY_BITS = 256
    }
}
