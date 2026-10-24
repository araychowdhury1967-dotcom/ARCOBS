package com.mobilestreaming.studio.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores only AES-GCM ciphertext in preferences; the key never leaves Android Keystore. */
class SecureStreamKeyStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("secure_stream_credentials", Context.MODE_PRIVATE)

    @Synchronized
    fun read(): String {
        val encoded = preferences.getString(PREF_KEY, null) ?: return ""
        return runCatching {
            val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
            require(encrypted.size > GCM_IV_BYTES + GCM_TAG_BYTES) { "Invalid encrypted credential" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, encrypted, 0, GCM_IV_BYTES),
            )
            val plaintext = cipher.doFinal(encrypted, GCM_IV_BYTES, encrypted.size - GCM_IV_BYTES)
            String(plaintext, Charsets.UTF_8).also { plaintext.fill(0) }
        }.getOrElse {
            // A restored backup or invalidated Keystore key cannot be decrypted safely.
            preferences.edit().remove(PREF_KEY).apply()
            ""
        }
    }

    @Synchronized
    fun write(value: String) {
        if (value.isEmpty()) {
            preferences.edit().remove(PREF_KEY).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val plaintext = value.toByteArray(Charsets.UTF_8)
        val ciphertext = cipher.doFinal(plaintext)
        plaintext.fill(0)
        val combined = cipher.iv + ciphertext
        preferences.edit().putString(PREF_KEY, Base64.encodeToString(combined, Base64.NO_WRAP)).apply()
    }

    @Synchronized
    fun clear() = preferences.edit().remove(PREF_KEY).apply()

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "mobile_streaming_studio_stream_key_v1"
        const val PREF_KEY = "stream_key_ciphertext"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BYTES = 16
        const val GCM_TAG_BITS = 128
    }
}
