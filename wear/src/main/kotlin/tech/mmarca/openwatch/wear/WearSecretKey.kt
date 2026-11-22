package tech.mmarca.openwatch.wear

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import tech.mmarca.openwatch.wearlink.SecretSeal

/**
 * The watch's key for [SecretSeal], made in the Android Keystore on first use and kept there,
 * so the phones' tokens open on this watch only. One per process, held by [WearGraph]; null
 * when the Keystore fails, and asked again next time.
 */
class WearSecretKey : () -> SecretKey? {

    private companion object {
        /** Persisted: a new alias is a new key, and every allowed phone would be asked about again. */
        const val ALIAS = "openwatch_wear_secrets_v1"
        const val PROVIDER = "AndroidKeyStore"
    }

    @Volatile
    private var key: SecretKey? = null

    override fun invoke(): SecretKey? = key ?: synchronized(this) {
        key ?: runCatching { loadOrCreate() }.getOrNull()?.also { key = it }
    }

    private fun loadOrCreate(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
