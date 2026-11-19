package com.digitallock.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.digitallock.BuildConfig
import com.digitallock.cache.LastKnownGoodCacheManager
import com.digitallock.graphics.NativeGlassBridge
import java.io.File

data class IntegrityReport(
    val packageNameValid: Boolean,
    val signingCertSha256: String,
    val signingCertMatchesExpected: Boolean,
    val isDebuggableBuild: Boolean,
    val nativeGlassLoaded: Boolean,
    val nativeRustSecurityLoaded: Boolean,
    val localCacheHealthy: Boolean,
    val warnings: List<String>,
) {
    val isClean: Boolean get() = warnings.isEmpty()
}

/**
 * Offline-capable and online-compatible APK & resource integrity coordinator.
 *
 * Strictly adheres to anti-lockout policy:
 * - Never treats offline mode as tampering.
 * - Never blocks PIN verification or locks the user out permanently.
 */
class IntegrityCoordinator(private val context: Context) {

    fun runLocalIntegrityCheck(): IntegrityReport {
        val warnings = mutableListOf<String>()

        val pkgValid = context.packageName == EXPECTED_PACKAGE_NAME
        if (!pkgValid) {
            warnings.add("Tên gói ứng dụng không khớp chuẩn.")
        }

        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable && !BuildConfig.DEBUG) {
            warnings.add("Phát hiện cờ debug bất thường trên bản phát hành.")
        }

        val certSha = readSigningCertSha256()
        val expectedCert = BuildConfig.EXPECTED_SIGNING_CERT_SHA256.trim()
        val certMatches = expectedCert.isEmpty() || certSha.equals(expectedCert, ignoreCase = true)
        if (!certMatches) {
            warnings.add("Chứng chỉ ký APK không khớp chữ ký phát hành.")
        }

        val glassLoaded = NativeGlassBridge.isAvailable
        val rustLoaded = NativeSecurityBridge.isNativeRustActive
        if (!glassLoaded) {
            warnings.add("Đang dùng chế độ đồ họa dự phòng (chưa nạp libdigitallock_glass.so).")
        }
        if (!rustLoaded) {
            warnings.add("Đang dùng bộ xác minh JCA dự phòng (chưa nạp libdigitallock_security.so).")
        }

        val cacheManager = LastKnownGoodCacheManager.getInstance(context)
        val manifestFile = File(cacheManager.activeDir, LastKnownGoodCacheManager.MANIFEST_FILE_NAME)
        val cacheHealthy = !cacheManager.isFirstInitializationCompleted() || (manifestFile.exists() && manifestFile.length() > 0L)
        if (!cacheHealthy) {
            warnings.add("Dữ liệu giao diện cục bộ không đầy đủ, sẽ khôi phục từ bản dự phòng.")
        }

        return IntegrityReport(
            packageNameValid = pkgValid,
            signingCertSha256 = certSha,
            signingCertMatchesExpected = certMatches,
            isDebuggableBuild = isDebuggable,
            nativeGlassLoaded = glassLoaded,
            nativeRustSecurityLoaded = rustLoaded,
            localCacheHealthy = cacheHealthy,
            warnings = warnings,
        )
    }

    private fun readSigningCertSha256(): String {
        return try {
            val pm = context.packageManager
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.signatures
            }
            val firstBytes = signatures?.firstOrNull()?.toByteArray() ?: return ""
            NativeSecurityBridge.sha256Hex(firstBytes)
        } catch (_: Throwable) {
            ""
        }
    }

    companion object {
        private const val EXPECTED_PACKAGE_NAME = "com.digitallock"
    }
}
