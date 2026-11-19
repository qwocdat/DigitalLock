package com.digitallock.security

import android.content.Context
import android.util.Base64
import com.digitallock.BuildConfig
import com.digitallock.cache.EncryptedBundleStore
import com.digitallock.network.NetworkConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

data class VerifiedLicenseSession(
    val plan: String,
    val sessionId: String,
    val expiresAt: String,
    val scopes: List<String>,
    val verifiedByServer: Boolean,
)

/**
 * Server-Verified License Authority client.
 *
 * Anti-NoKey Guarantees:
 * - Never stores raw license keys in SharedPreferences or logs.
 * - Never grants VIP or license validity locally; requires Ed25519 signature from
 *   `LICENSE_PUBLIC_KEY_B64` (separate from UI manifest key) + matching nonce & deviceId.
 * - Protected Backend endpoints (`/ui_manifest`, `/ui_assets`, `/features`) independently
 *   enforce session token + device binding + license status on the server.
 */
class ServerLicenseManager(context: Context) {

    private val appContext = context.applicationContext
    private val secureRandom = SecureRandom()
    private val sessionFile = File(appContext.filesDir, "digital_lock/integrity/session.enc")

    @Volatile
    private var cachedSessionToken: String? = null

    @Volatile
    private var currentSessionMeta: VerifiedLicenseSession? = null

    init {
        loadEncryptedSessionFromDisk()
    }

    fun hasStoredSession(): Boolean = !cachedSessionToken.isNullOrBlank()

    fun currentSessionToken(): String? = cachedSessionToken

    fun currentPlan(): String = currentSessionMeta?.plan ?: "unverified"

    fun clearSession() {
        cachedSessionToken = null
        currentSessionMeta = null
        if (sessionFile.exists()) sessionFile.delete()
    }

    fun generateNonce(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    suspend fun registerDevice(): Boolean = withContext(Dispatchers.IO) {
        try {
            val nonce = generateNonce()
            val ts = System.currentTimeMillis() / 1000L
            val body = JSONObject().apply {
                put("device_id", DeviceKeyManager.getDeviceId())
                put("device_pub_key_b64", DeviceKeyManager.getPublicKeyBase64())
                put("app_version_code", BuildConfig.VERSION_CODE)
                put("nonce", nonce)
                put("timestamp", ts)
            }.toString()

            val conn = NetworkConfig.openSecurePostConnection(NetworkConfig.deviceRegisterEndpoint())
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun activateLicense(rawLicenseKey: String): Result<VerifiedLicenseSession> = withContext(Dispatchers.IO) {
        val trimmed = rawLicenseKey.trim()
        if (trimmed.length < 12) {
            return@withContext Result.failure(IllegalArgumentException("Mã bản quyền không hợp lệ."))
        }
        if (!registerDevice()) {
            return@withContext Result.failure(IllegalStateException("Không thể đăng ký thiết bị với máy chủ."))
        }
        try {
            val nonce = generateNonce()
            val ts = System.currentTimeMillis() / 1000L
            val deviceId = DeviceKeyManager.getDeviceId()
            val body = JSONObject().apply {
                put("license_key", trimmed)
                put("device_id", deviceId)
                put("nonce", nonce)
                put("timestamp", ts)
            }.toString()

            val conn = NetworkConfig.openSecurePostConnection(NetworkConfig.licenseActivateEndpoint())
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val respText = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)?.readText().orEmpty()
            conn.disconnect()

            if (code !in 200..299) {
                return@withContext Result.failure(IllegalStateException("Kích hoạt bản quyền không thành công."))
            }

            val json = JSONObject(respText)
            val token = json.optString("session_token", "")
            val payloadRaw = json.optString("license_payload_raw", "")
            val sigB64 = json.optString("signature_b64", "")
            if (token.isBlank() || payloadRaw.isBlank() || sigB64.isBlank()) {
                return@withContext Result.failure(SecurityException("Phản hồi bản quyền không hợp lệ."))
            }

            val verified = verifySignedLicensePayload(payloadRaw, sigB64, nonce, deviceId)
                ?: return@withContext Result.failure(SecurityException("Xác minh chữ ký bản quyền thất bại."))

            persistEncryptedSession(token, verified)
            Result.success(verified)
        } catch (_: Throwable) {
            Result.failure(IllegalStateException("Không thể kết nối máy chủ bản quyền."))
        }
    }

    suspend fun verifyActiveSessionWithServer(): Boolean = withContext(Dispatchers.IO) {
        val token = cachedSessionToken ?: return@withContext false
        try {
            val nonce = generateNonce()
            val ts = System.currentTimeMillis() / 1000L
            val deviceId = DeviceKeyManager.getDeviceId()
            val conn = NetworkConfig.openSecureConnection(
                urlString = NetworkConfig.licenseVerifyEndpoint(),
                sessionToken = token,
                deviceId = deviceId,
                nonce = nonce,
                timestampSec = ts,
            )
            val code = conn.responseCode
            if (code == 401 || code == 403) {
                conn.disconnect()
                clearSession()
                return@withContext false
            }
            if (code !in 200..299) {
                conn.disconnect()
                return@withContext false
            }
            val respText = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            conn.disconnect()

            val json = JSONObject(respText)
            val payloadRaw = json.optString("license_payload_raw", "")
            val sigB64 = json.optString("signature_b64", "")
            val verified = verifySignedLicensePayload(payloadRaw, sigB64, nonce, deviceId)
            if (verified != null) {
                persistEncryptedSession(token, verified)
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun verifySignedLicensePayload(
        payloadRaw: String,
        signatureB64: String,
        expectedNonce: String,
        expectedDeviceId: String,
    ): VerifiedLicenseSession? {
        val sigOk = NativeSecurityBridge.verifyEd25519(
            message = payloadRaw.toByteArray(StandardCharsets.UTF_8),
            signatureB64 = signatureB64,
            publicKeyB64 = NetworkConfig.licensePublicKeyB64,
        )
        if (!sigOk) return null

        val obj = JSONObject(payloadRaw)
        if (!obj.optBoolean("license_valid", false)) return null
        if (obj.optString("nonce") != expectedNonce) return null
        if (obj.optString("device_id") != expectedDeviceId) return null

        val scopesArr = obj.optJSONArray("scopes")
        val scopes = mutableListOf<String>()
        if (scopesArr != null) {
            for (i in 0 until scopesArr.length()) {
                scopes.add(scopesArr.optString(i))
            }
        }
        return VerifiedLicenseSession(
            plan = obj.optString("plan", "standard"),
            sessionId = obj.optString("session_id", ""),
            expiresAt = obj.optString("expires_at", ""),
            scopes = scopes,
            verifiedByServer = true,
        )
    }

    private fun persistEncryptedSession(token: String, meta: VerifiedLicenseSession) {
        cachedSessionToken = token
        currentSessionMeta = meta
        val stored = JSONObject().apply {
            put("token", token)
            put("plan", meta.plan)
            put("session_id", meta.sessionId)
            put("expires_at", meta.expiresAt)
        }.toString()
        EncryptedBundleStore.writeEncryptedFile(
            sessionFile,
            stored.toByteArray(StandardCharsets.UTF_8),
            "digitallock_session_v3",
        )
    }

    private fun loadEncryptedSessionFromDisk() {
        val bytes = EncryptedBundleStore.readEncryptedFile(sessionFile, "digitallock_session_v3") ?: return
        try {
            val obj = JSONObject(String(bytes, StandardCharsets.UTF_8))
            val tok = obj.optString("token", "")
            if (tok.isNotBlank()) {
                cachedSessionToken = tok
                currentSessionMeta = VerifiedLicenseSession(
                    plan = obj.optString("plan", "standard"),
                    sessionId = obj.optString("session_id", ""),
                    expiresAt = obj.optString("expires_at", ""),
                    scopes = emptyList(),
                    verifiedByServer = false,
                )
            }
        } catch (_: Throwable) {
            clearSession()
        }
    }

    companion object {
        @Volatile
        private var instance: ServerLicenseManager? = null

        fun getInstance(context: Context): ServerLicenseManager {
            return instance ?: synchronized(this) {
                instance ?: ServerLicenseManager(context).also { instance = it }
            }
        }
    }
}
