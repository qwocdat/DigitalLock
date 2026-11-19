package com.digitallock.cache

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encrypted at-rest file store backed by Android Keystore.
 *
 * Used by [LastKnownGoodCacheManager] and [com.digitallock.runtime.SecureWebViewRuntime] so
 * cached HTML/CSS/JS frontend bundles are never stored in plaintext on shared storage,
 * and any disk tampering fails GCM authentication immediately.
 */
object EncryptedBundleStore {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val CACHE_KEY_ALIAS = "digitallock_lkg_cache_aes_gcm_v3"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    @Volatile
    private var cachedKey: SecretKey? = null

    private fun getOrCreateKey(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = ks.getEntry(CACHE_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) {
                return existing.secretKey.also { cachedKey = it }
            }
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                CACHE_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
            kg.init(spec)
            return kg.generateKey().also { cachedKey = it }
        }
    }

    fun writeEncryptedFile(targetFile: File, plaintext: ByteArray, relativePathAad: String) {
        targetFile.parentFile?.mkdirs()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(relativePathAad.toByteArray(StandardCharsets.UTF_8))
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        val blob = ByteBuffer.allocate(1 + iv.size + ciphertext.size)
            .put(iv.size.toByte())
            .put(iv)
            .put(ciphertext)
            .array()
        targetFile.writeBytes(blob)
    }

    fun readEncryptedFile(sourceFile: File, relativePathAad: String): ByteArray? {
        if (!sourceFile.exists() || !sourceFile.isFile) return null
        return try {
            val blob = sourceFile.readBytes()
            val buf = ByteBuffer.wrap(blob)
            val ivSize = buf.get().toInt() and 0xFF
            if (ivSize !in 12..16 || blob.size <= 1 + ivSize) return null
            val iv = ByteArray(ivSize)
            buf.get(iv)
            val ciphertext = ByteArray(blob.size - 1 - ivSize)
            buf.get(ciphertext)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(relativePathAad.toByteArray(StandardCharsets.UTF_8))
            cipher.doFinal(ciphertext)
        } catch (_: Throwable) {
            null
        }
    }
}
