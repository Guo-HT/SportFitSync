package com.sportfit.sync.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (platform passwords and OAuth tokens) with a
 * hardware-backed AES-256-GCM key held in the Android Keystore.
 *
 * The keystore never exposes the raw key, so the encrypted blobs stored in
 * Room are useless without the device's unlocked keystore. The 12-byte GCM IV
 * is prefixed to the ciphertext and the whole thing is Base64 encoded:
 *
 * ```
 * base64( iv[12] || ciphertext || gcmTag[16] )
 * ```
 */
object CryptoBox {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "sportfit.credentials.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128
    private const val PREFIX = "v1:"

    @Volatile
    private var cachedKey: SecretKey? = null

    private fun secretKey(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            val key = existing?.secretKey ?: generateKey()
            cachedKey = key
            return key
        }
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + cipherText
        return PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    fun decryptOrNull(cipherText: String?): String? {
        if (cipherText.isNullOrEmpty()) return null
        if (!cipherText.startsWith(PREFIX)) return null
        return try {
            val packed = Base64.decode(cipherText.removePrefix(PREFIX), Base64.NO_WRAP)
            if (packed.size <= IV_LENGTH) return null
            val iv = packed.copyOfRange(0, IV_LENGTH)
            val body = packed.copyOfRange(IV_LENGTH, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (_: Exception) {
            // Key rotated, data corrupted, or value written by another install.
            null
        }
    }

    fun decrypt(cipherText: String?): String = decryptOrNull(cipherText).orEmpty()

    /** True when the value looks like a blob produced by [encrypt]. */
    fun isEncrypted(value: String?): Boolean = value != null && value.startsWith(PREFIX)

    /** Wipes the keystore key; every stored secret becomes undecryptable. */
    fun reset() {
        synchronized(this) {
            cachedKey = null
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        }
    }
}
