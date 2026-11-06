package com.digitallock.data

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import com.digitallock.accessibility.AccessibilityMonitor
import com.digitallock.lock.LockLaunchDiagnostic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Inspects real Android system security state (`KeyguardManager`, `DevicePolicyManager`,
 * `PowerManager` battery optimization exemptions, `AccessibilityService` state) and records
 * honest diagnostics whenever Android 10–14+ background activity start restrictions prevent
 * `LockActivity` from auto-launching.
 */
class LockRepository(private val context: Context) {

    private val dpContext = context.applicationContext.createDeviceProtectedStorageContext()
    private val prefs = dpContext.getSharedPreferences(DIAG_PREFS, Context.MODE_PRIVATE)

    private val _lastDiagnostic = MutableStateFlow(loadLastDiagnostic())
    val lastDiagnostic: StateFlow<LockLaunchDiagnostic> = _lastDiagnostic.asStateFlow()

    data class SystemSecuritySnapshot(
        val isAndroidDeviceSecure: Boolean,
        val isKeyguardSecure: Boolean,
        val isKeyguardLockedNow: Boolean,
        val isDeviceOwnerProvisioned: Boolean,
        val isLockTaskPermitted: Boolean,
        val isAccessibilityMonitorEnabled: Boolean,
        val isIgnoringBatteryOptimizations: Boolean,
        val isHyperOsOrMiui: Boolean,
    )

    /**
     * Queries `KeyguardManager` and `DevicePolicyManager` for real system lock state.
     *
     * Note on Android platform semantics: `KeyguardManager.isDeviceSecure` returns `true`
     * whenever the user has configured a secure system lock screen (PIN, Pattern, or Password),
     * and `false` when the system lock is set to None or Swipe. Android intentionally does not
     * expose which specific credential subtype (PIN vs. Password vs. Pattern) is active to
     * non-system apps.
     */
    fun querySystemSecuritySnapshot(): SystemSecuritySnapshot {
        val km = context.getSystemService(KeyguardManager::class.java)
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)

        val pkg = context.packageName
        val isDeviceOwner = dpm?.isDeviceOwnerApp(pkg) == true
        val isLockTaskAllowed = dpm?.isLockTaskPermitted(pkg) == true

        return SystemSecuritySnapshot(
            isAndroidDeviceSecure = km?.isDeviceSecure == true,
            isKeyguardSecure = km?.isKeyguardSecure == true,
            isKeyguardLockedNow = km?.isKeyguardLocked == true,
            isDeviceOwnerProvisioned = isDeviceOwner,
            isLockTaskPermitted = isLockTaskAllowed,
            isAccessibilityMonitorEnabled = isAccessibilityServiceEnabled(),
            isIgnoringBatteryOptimizations = pm?.isIgnoringBatteryOptimizations(pkg) == true,
            isHyperOsOrMiui = isXiaomiHyperOsOrMiui(),
        )
    }

    fun recordLaunchDiagnostic(diagnostic: LockLaunchDiagnostic) {
        prefs.edit()
            .putString(KEY_LAST_STATUS, diagnostic.status.name)
            .putString(KEY_LAST_REASON, diagnostic.reasonMessage)
            .putLong(KEY_LAST_TIMESTAMP, diagnostic.timestampEpochMs)
            .commit()
        _lastDiagnostic.value = diagnostic
    }

    private fun loadLastDiagnostic(): LockLaunchDiagnostic {
        val statusName = prefs.getString(KEY_LAST_STATUS, LockLaunchDiagnostic.Status.IDLE.name)
        val status = try {
            LockLaunchDiagnostic.Status.valueOf(statusName ?: LockLaunchDiagnostic.Status.IDLE.name)
        } catch (_: IllegalArgumentException) {
            LockLaunchDiagnostic.Status.IDLE
        }
        val reason = prefs.getString(
            KEY_LAST_REASON,
            "No background lock launch failures recorded.",
        ) ?: ""
        val ts = prefs.getLong(KEY_LAST_TIMESTAMP, 0L)
        return LockLaunchDiagnostic(status = status, reasonMessage = reason, timestampEpochMs = ts)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val expectedComponent = "${context.packageName}/${AccessibilityMonitor::class.java.canonicalName ?: AccessibilityMonitor::class.java.name}"
        val shortComponent = "${context.packageName}/.accessibility.AccessibilityMonitor"
        return enabledServices.contains(expectedComponent, ignoreCase = true) ||
            enabledServices.contains(shortComponent, ignoreCase = true)
    }

    private fun isXiaomiHyperOsOrMiui(): Boolean {
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        val brand = android.os.Build.BRAND.lowercase()
        return manufacturer.contains("xiaomi") || brand.contains("xiaomi") ||
            brand.contains("redmi") || brand.contains("poco")
    }

    companion object {
        private const val DIAG_PREFS = "digitallock_launch_diagnostics_dp"
        private const val KEY_LAST_STATUS = "last_launch_status"
        private const val KEY_LAST_REASON = "last_launch_reason"
        private const val KEY_LAST_TIMESTAMP = "last_launch_timestamp"
    }
}
