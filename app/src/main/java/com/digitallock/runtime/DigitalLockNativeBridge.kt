package com.digitallock.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.webkit.JavascriptInterface
import com.digitallock.cache.LastKnownGoodCacheManager
import com.digitallock.data.LockOperationMode
import com.digitallock.data.LockRepository
import com.digitallock.data.SettingsRepository
import com.digitallock.lock.LockController
import com.digitallock.security.IntegrityCoordinator
import com.digitallock.security.PinManager
import com.digitallock.security.RecoveryManager
import com.digitallock.security.SafeRecoveryGuard
import com.digitallock.security.ServerLicenseManager
import org.json.JSONObject

/**
 * Strict allowlisted Android Native Bridge exposed to the Backend-Hosted Frontend inside [SecureWebViewRuntime].
 *
 * Security Rules:
 * - Only operates when invoked from the verified virtual origin (`https://appassets.digitallock.local`).
 * - Exposes ONLY explicitly allowlisted Android adapter actions (Lock, PIN, Recovery, Optical Tuning, Settings).
 * - Zero shell access, zero arbitrary file access, zero license/VIP mutation methods.
 */
class DigitalLockNativeBridge(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val lockRepository: LockRepository,
    private val pinManager: PinManager,
    private val recoveryManager: RecoveryManager,
    private val cacheManager: LastKnownGoodCacheManager,
    private val licenseManager: ServerLicenseManager,
    private val integrityCoordinator: IntegrityCoordinator,
    private val originVerifier: () -> Boolean,
    private val onSyncRequested: () -> Unit,
) {

    @JavascriptInterface
    fun getRuntimeStateJson(): String {
        if (!originVerifier()) return "{}"
        val s = settingsRepository.currentSettings()
        val snap = lockRepository.querySystemSecuritySnapshot()
        return JSONObject().apply {
            put("uiVersionCode", cacheManager.getActiveVersionCode())
            put("licensePlan", licenseManager.currentPlan())
            put("lockEnabled", s.lockEnabled)
            put("preventScreenshots", s.preventScreenshotsOnLock)
            put("biometricEnabled", s.biometricEnabled)
            put("refractionScale", s.refractionIntensityScale.toDouble())
            put("aberrationScale", s.chromaticAberrationScale.toDouble())
            put("a11yEnabled", snap.isAccessibilityMonitorEnabled)
            put("overlayEnabled", snap.canDrawOverlays)
            put("batteryIgnored", snap.isIgnoringBatteryOptimizations)
            put("pinConfigured", pinManager.isPrimaryPinConfigured())
        }.toString()
    }

    @JavascriptInterface
    fun getIntegritySummaryJson(): String {
        if (!originVerifier()) return "{}"
        val report = integrityCoordinator.runLocalIntegrityCheck()
        return JSONObject().apply {
            put("nativeRustLoaded", report.nativeRustSecurityLoaded)
            put("nativeGlassLoaded", report.nativeGlassLoaded)
            put("localCacheHealthy", report.localCacheHealthy)
            put("safeRecoveryActive", SafeRecoveryGuard.isSafeRecoveryMode(context))
        }.toString()
    }

    @JavascriptInterface
    fun setLockEnabled(enabled: Boolean) {
        if (!originVerifier()) return
        if (enabled && !pinManager.isPrimaryPinConfigured()) return
        settingsRepository.updateSetupCompleted(true, LockOperationMode.STANDALONE_SINGLE_LOCK)
        settingsRepository.setLockEnabled(enabled)
    }

    @JavascriptInterface
    fun triggerLockNow() {
        if (!originVerifier()) return
        if (!pinManager.isPrimaryPinConfigured()) return
        settingsRepository.updateSetupCompleted(true, LockOperationMode.STANDALONE_SINGLE_LOCK)
        LockController(context, settingsRepository, lockRepository).requestLockScreen("BackendUiManualLock")
    }

    @JavascriptInterface
    fun requestUiSync() {
        if (!originVerifier()) return
        onSyncRequested()
    }

    @JavascriptInterface
    fun setupPrimaryPin(pin: String, confirm: String): String {
        if (!originVerifier()) return JSONObject().put("ok", false).toString()
        return when (val res = pinManager.setupPrimaryPin(pin, confirm, allowAlphanumeric = false)) {
            is PinManager.PinSetupResult.Success -> {
                settingsRepository.updateSetupCompleted(true, LockOperationMode.STANDALONE_SINGLE_LOCK)
                JSONObject().put("ok", true).put("message", "Đã lưu mã PIN an toàn vào Android Keystore.").toString()
            }
            is PinManager.PinSetupResult.ValidationError -> {
                JSONObject().put("ok", false).put("message", res.message).toString()
            }
        }
    }

    @JavascriptInterface
    fun generateRecoveryCode(): String {
        if (!originVerifier()) return ""
        return recoveryManager.generateAndStoreOneTimeRecoveryCode()
    }

    @JavascriptInterface
    fun updateOpticalTuning(refractionScale: Float, aberrationScale: Float) {
        if (!originVerifier()) return
        settingsRepository.setOpticalTuning(refractionScale, aberrationScale)
    }

    @JavascriptInterface
    fun setPreventScreenshots(prevent: Boolean) {
        if (!originVerifier()) return
        settingsRepository.setPreventScreenshotsOnLock(prevent)
    }

    @JavascriptInterface
    fun setBiometricEnabled(enabled: Boolean) {
        if (!originVerifier()) return
        settingsRepository.setBiometricEnabled(enabled)
    }

    @JavascriptInterface
    fun openSystemSetting(target: String) {
        if (!originVerifier()) return
        val intent = when (target) {
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "overlay" -> Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
            "battery" -> Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            )
            else -> return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Throwable) {
        }
    }
}
