package com.digitallock.lock

import android.app.ActivityOptions
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.digitallock.data.LockRepository
import com.digitallock.data.SettingsRepository
import com.digitallock.receiver.DigitalLockDeviceAdminReceiver

/**
 * Handles starting [LockActivity] and managing Device Owner Lock Task Mode when legitimately
 * provisioned.
 *
 * Strictly adheres to Android 10–14+ Background Activity Launch (BAL) rules:
 * - Does NOT use Overlay windows to fake a lock screen.
 * - Does NOT abuse `USE_FULL_SCREEN_INTENT` as a BAL workaround.
 * - Records an accurate [LockLaunchDiagnostic] if the OS blocks background activity starts
 *   so the user is informed transparently and shown remediation options (Accessibility Service
 *   or Device Owner provisioning).
 */
class LockController(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val lockRepository: LockRepository,
) {

    fun requestLockScreen(triggerSource: String): Boolean {
        val settings = settingsRepository.currentSettings()
        if (!settings.setupCompleted || !settings.lockEnabled) {
            return false
        }

        SessionManager.lockSession()

        if (SessionManager.isLockActivityVisible.value) {
            return true
        }

        val snapshot = lockRepository.querySystemSecuritySnapshot()
        val canStartFromBackground = snapshot.isAccessibilityMonitorEnabled ||
            snapshot.isDeviceOwnerProvisioned

        return try {
            val intent = Intent(context, LockActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION
                )
                putExtra(EXTRA_TRIGGER_SOURCE, triggerSource)
            }

            val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }.toBundle()
            } else {
                null
            }

            context.startActivity(intent, options)

            if (!canStartFromBackground) {
                // On Android 10+, startActivity() from a background Service/BroadcastReceiver
                // without an active BAL exemption (such as an active AccessibilityService or
                // Device Owner role) may be silently aborted by ActivityTaskManager.
                lockRepository.recordLaunchDiagnostic(
                    LockLaunchDiagnostic(
                        status = LockLaunchDiagnostic.Status.BLOCKED_BY_BACKGROUND_RESTRICTIONS,
                        reasonMessage = "Triggered from $triggerSource while in background without AccessibilityMonitor or Device Owner exemption. Android 10–14+ may block background Activity launches.",
                        timestampEpochMs = System.currentTimeMillis(),
                    )
                )
            } else {
                lockRepository.recordLaunchDiagnostic(
                    LockLaunchDiagnostic(
                        status = LockLaunchDiagnostic.Status.LAUNCHED_SUCCESSFULLY,
                        reasonMessage = "LockActivity launched via $triggerSource.",
                        timestampEpochMs = System.currentTimeMillis(),
                    )
                )
            }
            true
        } catch (e: Throwable) {
            lockRepository.recordLaunchDiagnostic(
                LockLaunchDiagnostic(
                    status = LockLaunchDiagnostic.Status.BLOCKED_BY_BACKGROUND_RESTRICTIONS,
                    reasonMessage = "Android blocked LockActivity launch ($triggerSource): ${e.javaClass.simpleName}.",
                    timestampEpochMs = System.currentTimeMillis(),
                )
            )
            false
        }
    }

    /**
     * Configures Lock Task allowlist ONLY when the user has legitimately provisioned DigitalLock
     * as a Device Owner via `dpm set-device-owner`. Never fakes Device Owner status.
     */
    fun configureDeviceOwnerLockTaskIfProvisioned(): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        val pkg = context.packageName
        if (!dpm.isDeviceOwnerApp(pkg)) return false

        val adminComponent = ComponentName(context, DigitalLockDeviceAdminReceiver::class.java)
        return try {
            dpm.setLockTaskPackages(adminComponent, arrayOf(pkg))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(
                    adminComponent,
                    DevicePolicyManager.LOCK_TASK_FEATURE_NONE,
                )
            }
            true
        } catch (_: SecurityException) {
            false
        }
    }

    companion object {
        const val EXTRA_TRIGGER_SOURCE = "extra_trigger_source"
    }
}
