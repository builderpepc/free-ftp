package com.freeftp.core.store

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the secrets inside a saved connection profile. */
interface SecretCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String
}

/**
 * AES-GCM with a fresh random nonce per value.
 *
 * GCM authenticates as well as encrypts, so a tampered profile store fails to decrypt
 * rather than yielding a silently corrupted password. The nonce is prepended to the
 * ciphertext because it must be unique per encryption but need not be secret.
 */
class AesGcmSecretCipher(private val key: SecretKey) : SecretCipher {

    override fun encrypt(plaintext: String): String {
        // The nonce is deliberately NOT supplied by us. A key held in the Android key
        // store rejects a caller-provided IV outright ("Caller-provided IV not
        // permitted") because reusing one with GCM is catastrophic; both the key store
        // and the plain JCE providers generate a fresh random one when asked to.
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val nonce = cipher.iv
        check(nonce.size == NONCE_BYTES) {
            "expected a $NONCE_BYTES-byte GCM nonce but the provider produced ${nonce.size}"
        }
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(nonce + encrypted)
    }

    override fun decrypt(ciphertext: String): String {
        val raw = Base64.getDecoder().decode(ciphertext)
        require(raw.size > NONCE_BYTES) { "ciphertext is too short to contain a nonce" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, raw, 0, NONCE_BYTES))
        }
        return String(
            cipher.doFinal(raw, NONCE_BYTES, raw.size - NONCE_BYTES),
            Charsets.UTF_8,
        )
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128

        /** A fresh 256-bit AES key, for platforms without a hardware-backed key store. */
        fun generateKey(): SecretKey =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}
