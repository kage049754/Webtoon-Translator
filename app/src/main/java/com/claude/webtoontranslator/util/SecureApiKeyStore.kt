package com.claude.webtoontranslator.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the Gemini API key using an AES-256 key held by Android Keystore.
 *
 * The Keystore key is non-exportable; only ciphertext is persisted in DataStore.
 */
object SecureApiKeyStore {
    private const val KEY_ALIAS = "webtoon_translator_gemini_api_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE_BYTES = 12
    private const val TAG_SIZE_BITS = 128
    private const val PREFIX = "v1:"

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }

        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )

        return generator.generateKey()
    }

    fun encrypt(plainText: String): String {
        if (plainText.isBlank()) return ""

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

            val encrypted = cipher.doFinal(
                plainText.toByteArray(StandardCharsets.UTF_8)
            )

            val payload = ByteArray(IV_SIZE_BYTES + encrypted.size)
            System.arraycopy(cipher.iv, 0, payload, 0, IV_SIZE_BYTES)
            System.arraycopy(encrypted, 0, payload, IV_SIZE_BYTES, encrypted.size)

            PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }

    fun decrypt(storedValue: String): String {
        if (storedValue.isBlank()) return ""

        // Legacy versions stored the API key directly in DataStore.
        // Return it so SettingsDataStore can transparently migrate it on save.
        if (!storedValue.startsWith(PREFIX)) return storedValue

        return try {
            val payload = Base64.decode(
                storedValue.removePrefix(PREFIX),
                Base64.NO_WRAP
            )
            if (payload.size <= IV_SIZE_BYTES) return ""

            val iv = payload.copyOfRange(0, IV_SIZE_BYTES)
            val ciphertext = payload.copyOfRange(IV_SIZE_BYTES, payload.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(TAG_SIZE_BITS, iv)
            )

            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}
