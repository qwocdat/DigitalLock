package com.digitallock.remote

import android.content.Context
import com.digitallock.BuildConfig
import com.digitallock.cache.LastKnownGoodCacheManager
import com.digitallock.network.NetworkConfig
import com.digitallock.runtime.InjectSoLoader
import com.digitallock.security.DeviceKeyManager
import com.digitallock.security.NativeSecurityBridge
import com.digitallock.security.ServerLicenseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.random.Random

enum class SyncStatus {
    RUNNING,
    NEEDS_LICENSE_ACTIVATION,
    COMPLETED_ONLINE,
    COMPLETED_OFFLINE_LKG,
    ERROR_NO_INTERNET,
    ERROR_SERVER_UNREACHABLE,
    ERROR_DATA_FAILED,
}

data class SyncProgressState(
    val percent: Int = 0,
    val statusMessage: String = "Đang khởi động DIGITALLOCK...",
    val status: SyncStatus = SyncStatus.RUNNING,
)

/**
 * Real-time server loader enforcing:
 * 1. First Launch MUST have Internet + Server-Verified License + Signed UI Bundle.
 * 2. Protected UI endpoints (`/ui_manifest.php`, `/ui_assets.php`) require valid server session + device binding + replay nonce.
 * 3. Subsequent offline launches load AES-GCM encrypted, SHA-256 verified Last Known Good bundle seamlessly.
 */
class ServerSyncRepository(
    private val context: Context,
    private val cacheManager: LastKnownGoodCacheManager = LastKnownGoodCacheManager.getInstance(context),
    private val licenseManager: ServerLicenseManager = ServerLicenseManager.getInstance(context),
) {

    private val _progress = MutableStateFlow(SyncProgressState())
    val progress: StateFlow<SyncProgressState> = _progress.asStateFlow()

    suspend fun activateAndSync(rawLicenseKey: String): SyncProgressState = withContext(Dispatchers.IO) {
        emitProgress(15, "Đang kiểm tra quyền sử dụng...", SyncStatus.RUNNING)
        val actResult = licenseManager.activateLicense(rawLicenseKey)
        if (actResult.isFailure) {
            return@withContext emitProgress(
                percent = 0,
                message = actResult.exceptionOrNull()?.message ?: "Kích hoạt bản quyền không thành công.",
                status = SyncStatus.NEEDS_LICENSE_ACTIVATION,
            )
        }
        runLaunchSync()
    }

    suspend fun runLaunchSync(): SyncProgressState = withContext(Dispatchers.IO) {
        val isFirstLaunch = !cacheManager.isFirstInitializationCompleted()

        emitProgress(5, "Đang khởi động DIGITALLOCK...", SyncStatus.RUNNING)

        val online = NetworkConfig.isNetworkConnected(context)
        if (!online) {
            if (isFirstLaunch) {
                return@withContext emitProgress(
                    percent = 10,
                    message = "Không có kết nối Internet.",
                    status = SyncStatus.ERROR_NO_INTERNET,
                )
            } else {
                emitProgress(65, "Đang chuẩn bị màn hình khóa...", SyncStatus.RUNNING)
                cacheManager.loadVerifiedActiveIntoMemory()
                return@withContext emitProgress(
                    percent = 100,
                    message = "Đã hoàn tất.",
                    status = SyncStatus.COMPLETED_OFFLINE_LKG,
                )
            }
        }

        emitProgress(15, "Đang kết nối máy chủ...", SyncStatus.RUNNING)

        if (!licenseManager.hasStoredSession()) {
            return@withContext emitProgress(
                percent = 20,
                message = "Vui lòng kích hoạt bản quyền để tiếp tục.",
                status = SyncStatus.NEEDS_LICENSE_ACTIVATION,
            )
        }

        emitProgress(25, "Đang kiểm tra quyền sử dụng...", SyncStatus.RUNNING)
        val sessionValid = licenseManager.verifyActiveSessionWithServer()
        if (!sessionValid) {
            return@withContext emitProgress(
                percent = 20,
                message = "Phiên bản quyền không hợp lệ hoặc đã thu hồi.",
                status = SyncStatus.NEEDS_LICENSE_ACTIVATION,
            )
        }

        val syncOutcome = executeVerifiedSyncWithRetry(maxAttempts = if (isFirstLaunch) 3 else 2)
        if (syncOutcome == SyncOutcome.SUCCESS_UPDATED || syncOutcome == SyncOutcome.SUCCESS_NOT_MODIFIED) {
            emitProgress(95, "Đang hoàn tất...", SyncStatus.RUNNING)
            cacheManager.loadVerifiedActiveIntoMemory()
            return@withContext emitProgress(100, "Đã hoàn tất.", SyncStatus.COMPLETED_ONLINE)
        }

        if (isFirstLaunch) {
            val status = if (syncOutcome == SyncOutcome.SERVER_UNREACHABLE) {
                SyncStatus.ERROR_SERVER_UNREACHABLE
            } else {
                SyncStatus.ERROR_DATA_FAILED
            }
            val msg = if (status == SyncStatus.ERROR_SERVER_UNREACHABLE) {
                "Không thể kết nối máy chủ."
            } else {
                "Tải dữ liệu thất bại."
            }
            return@withContext emitProgress(0, msg, status)
        } else {
            emitProgress(85, "Đang chuẩn bị màn hình khóa...", SyncStatus.RUNNING)
            cacheManager.loadVerifiedActiveIntoMemory()
            return@withContext emitProgress(100, "Đã hoàn tất.", SyncStatus.COMPLETED_OFFLINE_LKG)
        }
    }

    suspend fun runSilentBackgroundSync(): Boolean = withContext(Dispatchers.IO) {
        if (!cacheManager.isFirstInitializationCompleted()) return@withContext false
        if (!licenseManager.hasStoredSession()) return@withContext false
        if (!NetworkConfig.isNetworkConnected(context)) return@withContext false
        if (!licenseManager.verifyActiveSessionWithServer()) return@withContext false
        val outcome = executeVerifiedSyncWithRetry(maxAttempts = 2)
        outcome == SyncOutcome.SUCCESS_UPDATED || outcome == SyncOutcome.SUCCESS_NOT_MODIFIED
    }

    private enum class SyncOutcome {
        SUCCESS_UPDATED,
        SUCCESS_NOT_MODIFIED,
        SERVER_UNREACHABLE,
        DATA_VERIFICATION_FAILED,
    }

    private suspend fun executeVerifiedSyncWithRetry(maxAttempts: Int): SyncOutcome {
        var lastOutcome = SyncOutcome.SERVER_UNREACHABLE
        for (attempt in 1..maxAttempts) {
            lastOutcome = executeSingleSyncAttempt()
            if (lastOutcome == SyncOutcome.SUCCESS_UPDATED || lastOutcome == SyncOutcome.SUCCESS_NOT_MODIFIED) {
                return lastOutcome
            }
            if (attempt < maxAttempts) {
                val backoffMs = (800L * (1L shl (attempt - 1))) + Random.nextLong(100L, 450L)
                delay(backoffMs)
            }
        }
        return lastOutcome
    }

    private fun executeSingleSyncAttempt(): SyncOutcome {
        cacheManager.clearStaging()
        val token = licenseManager.currentSessionToken() ?: return SyncOutcome.DATA_VERIFICATION_FAILED
        val deviceId = DeviceKeyManager.getDeviceId()

        return try {
            // 1. Bootstrap health check
            val bootConn = NetworkConfig.openSecureConnection(NetworkConfig.bootstrapEndpoint())
            val bootCode = try {
                bootConn.responseCode
            } finally {
                bootConn.disconnect()
            }
            if (bootCode !in 200..299) return SyncOutcome.SERVER_UNREACHABLE

            emitProgress(35, "Đang đồng bộ dữ liệu...", SyncStatus.RUNNING)

            // 2. Fetch protected signed UI manifest with ETag + session + device + replay nonce
            val existingEtag = if (cacheManager.isFirstInitializationCompleted()) cacheManager.getLastEtag() else null
            val manifestConn = NetworkConfig.openSecureConnection(
                urlString = NetworkConfig.uiManifestEndpoint(),
                etag = existingEtag,
                sessionToken = token,
                deviceId = deviceId,
                nonce = licenseManager.generateNonce(),
                timestampSec = System.currentTimeMillis() / 1000L,
            )
            val responseCode = manifestConn.responseCode
            if (responseCode == 304 && cacheManager.isFirstInitializationCompleted()) {
                manifestConn.disconnect()
                emitProgress(90, "Đang chuẩn bị màn hình khóa...", SyncStatus.RUNNING)
                return SyncOutcome.SUCCESS_NOT_MODIFIED
            }
            if (responseCode !in 200..299) {
                manifestConn.disconnect()
                return SyncOutcome.SERVER_UNREACHABLE
            }

            val newEtag = manifestConn.getHeaderField("ETag")
            val envelopeBytes = readBoundedBytes(manifestConn, NetworkConfig.MAX_MANIFEST_BYTES)
            manifestConn.disconnect()
            if (envelopeBytes == null) return SyncOutcome.DATA_VERIFICATION_FAILED

            val envelope = JSONObject(String(envelopeBytes, StandardCharsets.UTF_8))
            val manifestRaw = envelope.optString("manifest_raw", "")
            val signatureB64 = envelope.optString("signature_b64", "")
            if (manifestRaw.isBlank() || signatureB64.isBlank()) return SyncOutcome.DATA_VERIFICATION_FAILED

            // 3. Rust schema + Ed25519 manifest signature verification
            if (NativeSecurityBridge.validateManifestSchema(manifestRaw) !is NativeSecurityBridge.ManifestValidationResult.Valid) {
                return SyncOutcome.DATA_VERIFICATION_FAILED
            }
            val sigValid = NativeSecurityBridge.verifyEd25519(
                message = manifestRaw.toByteArray(StandardCharsets.UTF_8),
                signatureB64 = signatureB64,
                publicKeyB64 = NetworkConfig.manifestPublicKeyB64,
            )
            if (!sigValid) return SyncOutcome.DATA_VERIFICATION_FAILED

            val parsedManifest = RemoteUiConfig.parseFromVerifiedManifestJson(manifestRaw)
            val minAppVersion = JSONObject(manifestRaw).optInt("min_app_version_code", 1)
            if (BuildConfig.VERSION_CODE < minAppVersion) return SyncOutcome.DATA_VERIFICATION_FAILED

            emitProgress(55, "Đang tải dữ liệu lõi...", SyncStatus.RUNNING)

            // 4. Download, SHA-256 verify, and AES-GCM encrypt every frontend asset into staging
            val resources = parsedManifest.resources
            val totalRes = resources.size.coerceAtLeast(1)
            for ((idx, res) in resources.withIndex()) {
                if (cacheManager.hasVerifiedResourceWithSha(res.path, res.sha256)) {
                    cacheManager.copyActiveResourceToStaging(res.path)
                } else {
                    val resConn = NetworkConfig.openSecureConnection(
                        urlString = NetworkConfig.uiAssetEndpoint(res.path),
                        sessionToken = token,
                        deviceId = deviceId,
                    )
                    val resCode = resConn.responseCode
                    if (resCode !in 200..299) {
                        resConn.disconnect()
                        return SyncOutcome.DATA_VERIFICATION_FAILED
                    }
                    val bytes = readBoundedBytes(resConn, NetworkConfig.MAX_RESOURCE_BYTES)
                    resConn.disconnect()
                    if (bytes == null || bytes.size.toLong() != res.size) {
                        return SyncOutcome.DATA_VERIFICATION_FAILED
                    }
                    val actualSha = NativeSecurityBridge.sha256Hex(bytes)
                    if (!actualSha.equals(res.sha256, ignoreCase = true)) {
                        return SyncOutcome.DATA_VERIFICATION_FAILED
                    }
                    if (res.path == "inject.so" && InjectSoLoader.parseAndValidate(bytes) == null) {
                        return SyncOutcome.DATA_VERIFICATION_FAILED
                    }
                    cacheManager.writeStagingFile(res.path, bytes)
                }
                val pct = 55 + (((idx + 1) * 30) / totalRes)
                emitProgress(pct.coerceAtMost(85), "Đang tải dữ liệu lõi...", SyncStatus.RUNNING)
            }

            emitProgress(90, "Đang chuẩn bị màn hình khóa...", SyncStatus.RUNNING)

            val promoted = cacheManager.promoteStagingToActive(
                verifiedManifestJson = manifestRaw,
                signatureB64 = signatureB64,
                etag = newEtag,
                versionCode = parsedManifest.versionCode,
                versionName = parsedManifest.versionName,
            )
            if (!promoted) return SyncOutcome.DATA_VERIFICATION_FAILED
            SyncOutcome.SUCCESS_UPDATED
        } catch (_: java.io.IOException) {
            cacheManager.clearStaging()
            SyncOutcome.SERVER_UNREACHABLE
        } catch (_: Throwable) {
            cacheManager.clearStaging()
            SyncOutcome.DATA_VERIFICATION_FAILED
        }
    }

    private fun readBoundedBytes(conn: javax.net.ssl.HttpsURLConnection, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        conn.inputStream.use { input ->
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                total += read
                if (total > maxBytes) return null
                out.write(buf, 0, read)
            }
        }
        return out.toByteArray()
    }

    private fun emitProgress(percent: Int, message: String, status: SyncStatus): SyncProgressState {
        val next = SyncProgressState(
            percent = percent.coerceIn(0, 100),
            statusMessage = message,
            status = status,
        )
        _progress.value = next
        return next
    }
}
