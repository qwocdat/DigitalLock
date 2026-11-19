package com.digitallock.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Hardware-backed Android Keystore Device Binding manager.
 *
 * - Private EC P-256 key is generated inside `AndroidKeyStore` and cannot be exported.
 * - Derives a deterministic `deviceId` from the public key hash (no IMEI or MAC address).
 * - Signs server challenges & request nonces cleanly without exposing private key material.
 */
object DeviceKeyManager {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val DEVICE_KEY_ALIAS = "digitallock_device_bind_ec_v3"

    @Synchronized
    private fun ensureKeyPair(): KeyStore.PrivateKeyEntry {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getEntry(DEVICE_KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) return existing

        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            DEVICE_KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        kpg.initialize(spec)
        kpg.generateKeyPair()
        return ks.getEntry(DEVICE_KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
    }

    fun getPublicKeyBase64(): String {
        val entry = ensureKeyPair()
        return Base64.encodeToString(entry.certificate.publicKey.encoded, Base64.NO_WRAP)
    }

    fun getDeviceId(): String {
        val pubBytes = ensureKeyPair().certificate.publicKey.encoded
        val hex = NativeSecurityBridge.sha256Hex(pubBytes)
        return "dl_dev_${hex.take(32)}"
    }

    fun signPayloadBase64(payload: ByteArray): String {
        val entry = ensureKeyPair()
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(entry.privateKey)
        sig.update(payload)
        return Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
    }
}
