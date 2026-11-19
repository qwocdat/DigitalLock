package com.digitallock.runtime

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.digitallock.cache.LastKnownGoodCacheManager
import java.io.ByteArrayInputStream

/**
 * Hardened WebView Runtime rendering ONLY the verified, AES-GCM decrypted Backend-Hosted UI Bundle.
 *
 * Security Controls:
 * - Virtual HTTPS origin (`https://appassets.digitallock.local/`) served strictly via `shouldInterceptRequest`
 *   from verified Last Known Good cache (zero raw file:// access, zero external third-party scripts).
 * - Rejects mixed content, blocks all navigation outside `appassets.digitallock.local`.
 * - Enforces strict Content-Security-Policy response headers on HTML payloads.
 * - Verifies origin before permitting any [DigitalLockNativeBridge] call.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SecureWebViewRuntime(
    cacheManager: LastKnownGoodCacheManager,
    bridgeFactory: (originVerifier: () -> Boolean) -> DigitalLockNativeBridge,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }

    DisposableEffect(Unit) {
        onDispose {
            webViewHolder[0]?.let { wv ->
                wv.removeJavascriptInterface("DigitalLockBridge")
                wv.stopLoading()
                wv.destroy()
            }
            webViewHolder[0] = null
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    javaScriptCanOpenWindowsAutomatically = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    safeBrowsingEnabled = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    setSupportZoom(false)
                }

                val bridge = bridgeFactory {
                    val curUrl = this.url ?: return@bridgeFactory false
                    curUrl.startsWith("https://$VIRTUAL_HOST/")
                }
                addJavascriptInterface(bridge, "DigitalLockBridge")

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val uri = request?.url ?: return true
                        val allowed = uri.scheme == "https" && uri.host == VIRTUAL_HOST
                        return !allowed
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        val uri = request?.url ?: return emptyForbiddenResponse()
                        if (uri.scheme != "https" || uri.host != VIRTUAL_HOST) {
                            return emptyForbiddenResponse()
                        }
                        val relPath = (uri.path ?: "/index.html").trimStart('/').ifBlank { "index.html" }
                        val bytes = cacheManager.readVerifiedDecryptedAsset(relPath)
                            ?: return emptyNotFoundResponse()
                        val mime = guessMimeType(relPath)
                        val headers = mutableMapOf(
                            "X-Content-Type-Options" to "nosniff",
                            "Cache-Control" to "no-store",
                        )
                        if (mime == "text/html") {
                            headers["Content-Security-Policy"] =
                                "default-src 'self'; style-src 'self'; script-src 'self'; img-src 'self' data:; connect-src https://digitalxiao.shopaccvt.site; object-src 'none'; base-uri 'none';"
                        }
                        return WebResourceResponse(
                            mime,
                            "UTF-8",
                            200,
                            "OK",
                            headers,
                            ByteArrayInputStream(bytes),
                        )
                    }
                }

                webViewHolder[0] = this
                loadUrl("https://$VIRTUAL_HOST/index.html")
            }
        },
    )
}

private const val VIRTUAL_HOST = "appassets.digitallock.local"

private fun guessMimeType(path: String): String = when {
    path.endsWith(".html", true) -> "text/html"
    path.endsWith(".css", true) -> "text/css"
    path.endsWith(".js", true) -> "application/javascript"
    path.endsWith(".json", true) -> "application/json"
    path.endsWith(".png", true) -> "image/png"
    path.endsWith(".jpg", true) || path.endsWith(".jpeg", true) -> "image/jpeg"
    path.endsWith(".webp", true) -> "image/webp"
    path.endsWith(".svg", true) -> "image/svg+xml"
    else -> "application/octet-stream"
}

private fun emptyForbiddenResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))

private fun emptyNotFoundResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
