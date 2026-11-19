package com.digitallock.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.digitallock.BuildConfig
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/**
 * Centralized HTTPS-only Network Core for `https://digitalxiao.shopaccvt.site/digitallock`.
 * Uses two separate Ed25519 public verification keys (License vs. UI Manifest).
 */
object NetworkConfig {
    private const val ALLOWED_HOST = "digitalxiao.shopaccvt.site"

    val baseUrl: String
        get() = BuildConfig.BACKEND_BASE_URL.trimEnd('/')

    val manifestPublicKeyB64: String
        get() = BuildConfig.MANIFEST_PUBLIC_KEY_B64

    val licensePublicKeyB64: String
        get() = BuildConfig.LICENSE_PUBLIC_KEY_B64

    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 15_000
    const val MAX_MANIFEST_BYTES = 512 * 1024
    const val MAX_RESOURCE_BYTES = 15 * 1024 * 1024

    fun bootstrapEndpoint(): String = "$baseUrl/api/v1/bootstrap.php"
    fun deviceRegisterEndpoint(): String = "$baseUrl/api/v1/device_register.php"
    fun licenseActivateEndpoint(): String = "$baseUrl/api/v1/license_activate.php"
    fun licenseVerifyEndpoint(): String = "$baseUrl/api/v1/license_verify.php"
    fun licenseRefreshEndpoint(): String = "$baseUrl/api/v1/license_refresh.php"
    fun uiManifestEndpoint(): String = "$baseUrl/api/v1/ui_manifest.php"
    fun featuresEndpoint(): String = "$baseUrl/api/v1/features.php"
    fun integrityReportEndpoint(): String = "$baseUrl/api/v1/integrity_report.php"

    fun uiAssetEndpoint(relativePath: String): String {
        val clean = relativePath.trimStart('/')
        require(!clean.contains("..") && !clean.contains("\\")) { "Invalid asset path" }
        return "$baseUrl/api/v1/ui_assets.php?file=${URLEncoder.encode(clean, "UTF-8")}"
    }

    fun openSecureConnection(
        urlString: String,
        etag: String? = null,
        sessionToken: String? = null,
        deviceId: String? = null,
        nonce: String? = null,
        timestampSec: Long? = null,
    ): HttpsURLConnection {
        val conn = createValidatedHttpsConnection(urlString, "GET")
        if (!etag.isNullOrBlank()) conn.setRequestProperty("If-None-Match", etag)
        if (!sessionToken.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $sessionToken")
        if (!deviceId.isNullOrBlank()) conn.setRequestProperty("X-Device-Id", deviceId)
        if (!nonce.isNullOrBlank()) conn.setRequestProperty("X-Request-Nonce", nonce)
        if (timestampSec != null) conn.setRequestProperty("X-Request-Timestamp", timestampSec.toString())
        return conn
    }

    fun openSecurePostConnection(
        urlString: String,
        sessionToken: String? = null,
        deviceId: String? = null,
        nonce: String? = null,
        timestampSec: Long? = null,
    ): HttpsURLConnection {
        val conn = createValidatedHttpsConnection(urlString, "POST")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (!sessionToken.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $sessionToken")
        if (!deviceId.isNullOrBlank()) conn.setRequestProperty("X-Device-Id", deviceId)
        if (!nonce.isNullOrBlank()) conn.setRequestProperty("X-Request-Nonce", nonce)
        if (timestampSec != null) conn.setRequestProperty("X-Request-Timestamp", timestampSec.toString())
        return conn
    }

    private fun createValidatedHttpsConnection(urlString: String, method: String): HttpsURLConnection {
        val url = URL(urlString)
        require(url.protocol.equals("https", ignoreCase = true)) { "HTTPS required" }
        require(url.host.equals(ALLOWED_HOST, ignoreCase = true)) { "Host not allowed" }

        val conn = url.openConnection() as HttpsURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = false
        conn.requestMethod = method
        conn.setRequestProperty("Accept", "application/json, application/octet-stream")
        conn.setRequestProperty("User-Agent", "DigitalLock/${BuildConfig.VERSION_NAME} (Android)")
        conn.setRequestProperty("X-DigitalLock-App-Version", BuildConfig.VERSION_CODE.toString())
        return conn
    }

    fun isNetworkConnected(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
