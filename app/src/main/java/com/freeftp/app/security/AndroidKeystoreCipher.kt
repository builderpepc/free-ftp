package com.freeftp.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.freeftp.core.store.AesGcmSecretCipher
import com.freeftp.core.store.SecretCipher
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * A [SecretCipher] whose key lives in the Android key store.
 *
 * The key is generated once, marked non-exportable and never leaves the TEE (or the
 * secure element, on hardware that has one), so the saved passwords cannot be recovered
 * from a stolen backup of the app's files.
 */
object AndroidKeystoreCipher {

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "freeftp.profile-secrets"

    fun create(): SecretCipher = AesGcmSecretCipher(loadOrCreateKey())

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Deliberately not requiring user authentication: the app must be able to
                // reconnect for a background transfer without a fingerprint prompt.
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }
}
