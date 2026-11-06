package com.digitallock.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.SecretKeyFactory

/**
 * Hardware-backed credential storage for DigitalLock.
 *
 * Security architecture:
 * 1. Every credential (Primary PIN, Secondary PIN, Recovery Code, Security Question Answer)
 *    is hashed using PBKDF2-HMAC-SHA256 with 210,000 iterations and a unique 16-byte CSPRNG salt.
 * 2. The derived 256-bit hash and salt are then encrypted at rest using an AES-256-GCM master key
 *    stored inside the hardware-backed `AndroidKeyStore`.
 * 3. Device-protected storage (`createDeviceProtectedStorageContext()`) is used so that
 *    Direct Boot (`LOCKED_BOOT_COMPLETED`) can verify configuration state without crashing.
 * 4. Verification uses `MessageDigest.isEqual` (constant-time comparison).
 * 5. Zero plaintext credentials, zero hardcoded keys, zero sensitive logs.
 */
class CredentialStore(context: Context) {

    private val storageContext: Context = context.applicationContext.createDeviceProtectedStorageContext()
    private val prefs = storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    enum class CredentialSlot(val keyPrefix: String) {
        PRIMARY_PIN("slot_primary_pin"),
        SECONDARY_PIN("slot_secondary_pin"),
        RECOVERY_CODE("slot_recovery_code"),
        SECURITY_ANSWER("slot_security_answer"),
    }

    fun saveCredential(slot: CredentialSlot, secretChars: CharArray): Boolean {
        if (secretChars.isEmpty()) return false
        val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
        val derivedHash = derivePbkdf2Hash(secretChars, salt)
        return try {
            val payload = ByteBuffer.allocate(4 + SALT_BYTES + derivedHash.size)
                .putInt(PBKDF2_ITERATIONS)
                .put(salt)
                .put(derivedHash)
                .array()
            val encryptedBlob = encryptWithKeystore(payload, slot.keyPrefix.toByteArray(StandardCharsets.UTF_8))
            prefs.edit()
                .putString("${slot.keyPrefix}_blob", Base64.encodeToString(encryptedBlob, Base64.NO_WRAP))
                .commit()
        } finally {
            derivedHash.fill(0)
            salt.fill(0)
        }
    }

    fun verifyCredential(slot: CredentialSlot, candidateChars: CharArray): Boolean {
        if (candidateChars.isEmpty()) return false
        val encodedBlob = prefs.getString("${slot.keyPrefix}_blob", null) ?: return false
        val encryptedBlob = try {
            Base64.decode(encodedBlob, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val decryptedPayload = decryptWithKeystore(
            encryptedBlob,
            slot.keyPrefix.toByteArray(StandardCharsets.UTF_8),
        ) ?: return false

        if (decryptedPayload.size < 4 + SALT_BYTES + HASH_BYTES) {
            decryptedPayload.fill(0)
            return false
        }

        val buffer = ByteBuffer.wrap(decryptedPayload)
        val iterations = buffer.int
        val salt = ByteArray(SALT_BYTES)
        buffer.get(salt)
        val expectedHash = ByteArray(decryptedPayload.size - 4 - SALT_BYTES)
        buffer.get(expectedHash)

        val candidateHash = derivePbkdf2Hash(candidateChars, salt, iterations)
        return try {
            MessageDigest.isEqual(expectedHash, candidateHash)
        } finally {
            decryptedPayload.fill(0)
            salt.fill(0)
            expectedHash.fill(0)
            candidateHash.fill(0)
        }
    }

    fun hasCredential(slot: CredentialSlot): Boolean {
        return !prefs.getString("${slot.keyPrefix}_blob", null).isNullOrEmpty()
    }

    fun clearCredential(slot: CredentialSlot) {
        prefs.edit().remove("${slot.keyPrefix}_blob").apply()
    }

    fun savePrimaryPinLength(length: Int, isAlphanumeric: Boolean) {
        prefs.edit()
            .putInt(KEY_PRIMARY_PIN_LENGTH, length.coerceIn(4, 12))
            .putBoolean(KEY_PRIMARY_ALPHANUMERIC, isAlphanumeric)
            .apply()
    }

    fun getPrimaryPinLength(): Int = prefs.getInt(KEY_PRIMARY_PIN_LENGTH, 6).coerceIn(4, 12)

    fun isPrimaryAlphanumeric(): Boolean = prefs.getBoolean(KEY_PRIMARY_ALPHANUMERIC, false)

    fun saveSecurityQuestionPrompt(question: String) {
        prefs.edit().putString(KEY_SECURITY_QUESTION_PROMPT, question.trim()).apply()
    }

    fun getSecurityQuestionPrompt(): String? = prefs.getString(KEY_SECURITY_QUESTION_PROMPT, null)

    fun getFailedAttempts(): Int = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)

    fun getLockoutUntilEpochMs(): Long = prefs.getLong(KEY_LOCKOUT_UNTIL_MS, 0L)

    fun recordFailedAttempt(nowEpochMs: Long): Long {
        val nextAttempts = getFailedAttempts() + 1
        val cooldownDurationMs = computeBackoffDurationMs(nextAttempts)
        val lockoutUntil = if (cooldownDurationMs > 0L) nowEpochMs + cooldownDurationMs else 0L
        prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, nextAttempts)
            .putLong(KEY_LOCKOUT_UNTIL_MS, lockoutUntil)
            .commit()
        return lockoutUntil
    }

    fun resetFailedAttempts() {
        prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL_MS, 0L)
            .commit()
    }

    private fun derivePbkdf2Hash(
        secret: CharArray,
        salt: ByteArray,
        iterations: Int = PBKDF2_ITERATIONS,
    ): ByteArray {
        val spec = PBEKeySpec(secret, salt, iterations, HASH_BYTES * 8)
        return try {
            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) {
            return existing.secretKey
        }
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encryptWithKeystore(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        cipher.updateAAD(aad)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(1 + iv.size + ciphertext.size)
            .put(iv.size.toByte())
            .put(iv)
            .put(ciphertext)
            .array()
    }

    private fun decryptWithKeystore(blob: ByteArray, aad: ByteArray): ByteArray? {
        return try {
            val buffer = ByteBuffer.wrap(blob)
            val ivSize = buffer.get().toInt() and 0xFF
            if (ivSize !in 12..16 || blob.size <= 1 + ivSize) return null
            val iv = ByteArray(ivSize)
            buffer.get(iv)
            val ciphertext = ByteArray(blob.size - 1 - ivSize)
            buffer.get(ciphertext)

            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateMasterKey(),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertext)
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "digitallock_credentials_dp"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "digitallock_master_aes_gcm_v1"
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"

        const val PBKDF2_ITERATIONS = 210_000
        const val SALT_BYTES = 16
        const val HASH_BYTES = 32
        private const val GCM_TAG_BITS = 128

        private const val KEY_PRIMARY_PIN_LENGTH = "primary_pin_length"
        private const val KEY_PRIMARY_ALPHANUMERIC = "primary_pin_alphanumeric"
        private const val KEY_SECURITY_QUESTION_PROMPT = "security_question_prompt"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts_count"
        private const val KEY_LOCKOUT_UNTIL_MS = "lockout_until_epoch_ms"

        /**
         * Progressive rate-limiting curve:
         * - 1..4 failures: 0s cooldown
         * - 5 failures: 30s cooldown
         * - 6 failures: 60s cooldown
         * - 7 failures: 300s (5m) cooldown
         * - 8+ failures: capped at 900s (15m) cooldown (never permanently bricks the user)
         */
        fun computeBackoffDurationMs(failedAttempts: Int): Long {
            return when {
                failedAttempts < 5 -> 0L
                failedAttempts == 5 -> 30_000L
                failedAttempts == 6 -> 60_000L
                failedAttempts == 7 -> 300_000L
                else -> 900_000L
            }
        }
    }
}
