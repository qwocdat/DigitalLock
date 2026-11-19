package com.digitallock.security

import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * JNI bridge to the memory-safe Rust Security Engine (`libdigitallock_security.so`).
 *
 * Provides deterministic fallback to Android API 33+ JCA Ed25519 / SHA-256 when running
 * on an ABI without the prebuilt `.so` so security checks remain active and never brick unlock.
 */
object NativeSecurityBridge {

    private var nativeLoaded = false

    init {
        try {
            System.loadLibrary("digitallock_security")
            nativeLoaded = true
        } catch (_: UnsatisfiedLinkError) {
            nativeLoaded = false
        }
    }

    val isNativeRustActive: Boolean get() = nativeLoaded

    private external fun nativeGetEngineVersion(): String?
    private external fun nativeSha256Hex(input: ByteArray): String?
    private external fun nativeVerifyEd25519(
        message: ByteArray,
        signatureB64: String,
        publicKeyB64: String,
    ): Boolean
    private external fun nativeValidateManifestJson(manifestJson: String): String?
    private external fun nativeConstantTimeEquals(a: ByteArray, b: ByteArray): Boolean

    fun engineVersion(): String {
        if (nativeLoaded) {
            try {
                nativeGetEngineVersion()?.let { return it }
            } catch (_: Throwable) {
            }
        }
        return "digitallock-jca-fallback/2.0.0"
    }

    fun sha256Hex(data: ByteArray): String {
        if (nativeLoaded) {
            try {
                nativeSha256Hex(data)?.let { return it }
            } catch (_: Throwable) {
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun verifyEd25519(
        message: ByteArray,
        signatureB64: String,
        publicKeyB64: String,
    ): Boolean {
        if (signatureB64.isBlank() || publicKeyB64.isBlank()) return false
        if (nativeLoaded) {
            try {
                return nativeVerifyEd25519(message, signatureB64, publicKeyB64)
            } catch (_: Throwable) {
            }
        }
        return verifyEd25519FallbackJca(message, signatureB64, publicKeyB64)
    }

    fun validateManifestSchema(manifestJson: String): ManifestValidationResult {
        if (nativeLoaded) {
            try {
                val res = nativeValidateManifestJson(manifestJson)
                if (res == "OK") return ManifestValidationResult.Valid
                if (!res.isNullOrBlank()) return ManifestValidationResult.Invalid(res)
            } catch (_: Throwable) {
            }
        }
        return validateManifestFallback(manifestJson)
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (nativeLoaded) {
            try {
                return nativeConstantTimeEquals(a, b)
            } catch (_: Throwable) {
            }
        }
        return MessageDigest.isEqual(a, b)
    }

    private fun verifyEd25519FallbackJca(
        message: ByteArray,
        signatureB64: String,
        publicKeyB64: String,
    ): Boolean {
        return try {
            val sigBytes = Base64.decode(signatureB64.trim(), Base64.DEFAULT)
            val rawPub = Base64.decode(publicKeyB64.trim(), Base64.DEFAULT)
            val spkiBytes = if (rawPub.size == 32) {
                ED25519_SPKI_PREFIX + rawPub
            } else {
                rawPub
            }
            val keySpec = X509EncodedKeySpec(spkiBytes)
            val pubKey = KeyFactory.getInstance("Ed25519").generatePublic(keySpec)
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(pubKey)
            verifier.update(message)
            verifier.verify(sigBytes)
        } catch (_: Throwable) {
            false
        }
    }

    private fun validateManifestFallback(manifestJson: String): ManifestValidationResult {
        if (manifestJson.isBlank() || manifestJson.length > 512 * 1024) {
            return ManifestValidationResult.Invalid("manifest_size_out_of_bounds")
        }
        return try {
            val root = JSONObject(manifestJson)
            if (root.optInt("schema_version", 0) != 1) {
                return ManifestValidationResult.Invalid("unsupported_schema_version")
            }
            if (root.optLong("version_code", 0L) <= 0L || root.optString("version_name").isBlank()) {
                return ManifestValidationResult.Invalid("invalid_version_metadata")
            }
            if (root.optBoolean("revoked", false)) {
                return ManifestValidationResult.Invalid("manifest_revoked")
            }
            if (root.optJSONObject("lockscreen") == null || root.optJSONObject("pin_screen") == null) {
                return ManifestValidationResult.Invalid("missing_ui_sections")
            }
            val resources = root.optJSONArray("resources")
                ?: return ManifestValidationResult.Invalid("missing_resources")
            if (resources.length() > 64) {
                return ManifestValidationResult.Invalid("too_many_resources")
            }
            val allowedTypes = setOf("theme", "wallpaper", "animation", "shader_config", "icon", "font", "sound", "config")
            val forbiddenExts = listOf(".dex", ".jar", ".apk", ".sh", ".php", ".exe")
            for (i in 0 until resources.length()) {
                val item = resources.getJSONObject(i)
                val path = item.optString("path", "")
                val type = item.optString("type", "")
                val sha = item.optString("sha256", "")
                val size = item.optLong("size", 0L)
                if (type !in allowedTypes) return ManifestValidationResult.Invalid("disallowed_resource_type")
                if (path.isBlank() || path.startsWith("/") || path.contains("..") || path.contains("\\")) {
                    return ManifestValidationResult.Invalid("unsafe_resource_path")
                }
                val lower = path.lowercase()
                if (forbiddenExts.any { lower.endsWith(it) }) {
                    return ManifestValidationResult.Invalid("executable_resource_forbidden")
                }
                if (sha.length != 64 || !sha.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                    return ManifestValidationResult.Invalid("invalid_resource_sha256")
                }
                if (size <= 0L || size > 15L * 1024L * 1024L) {
                    return ManifestValidationResult.Invalid("resource_size_out_of_bounds")
                }
            }
            ManifestValidationResult.Valid
        } catch (_: Throwable) {
            ManifestValidationResult.Invalid("manifest_json_invalid")
        }
    }

    sealed class ManifestValidationResult {
        data object Valid : ManifestValidationResult()
        data class Invalid(val reasonCode: String) : ManifestValidationResult()
    }

    private val ED25519_SPKI_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    )
}
