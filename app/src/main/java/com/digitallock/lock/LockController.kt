package com.digitallock.lock

import android.app.ActivityOptions
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.digitallock.accessibility.AccessibilityMonitor
import com.digitallock.data.LockRepository
import com.digitallock.data.SettingsRepository
import com.digitallock.receiver.DigitalLockDeviceAdminReceiver

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

        // If AccessibilityMonitor is active, attach the un-dismissible TYPE_ACCESSIBILITY_OVERLAY
        // lock surface directly so Home/Recents swipe gestures are 100% blocked!
        val a11y = AccessibilityMonitor.instance
        if (a11y != null) {
            a11y.showUnDismissibleLockSurface()
            lockRepository.recordLaunchDiagnostic(
                LockLaunchDiagnostic(
                    status = LockLaunchDiagnostic.Status.LAUNCHED_SUCCESSFULLY,
                    reasonMessage = "Đã khóa toàn màn hình chống thoát ($triggerSource).",
                    timestampEpochMs = System.currentTimeMillis(),
                )
            )
            return true
        }

        if (SessionManager.isLockActivityVisible.value) {
            return true
        }

        return try {
            val intent = Intent(context, LockActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
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
            lockRepository.recordLaunchDiagnostic(
                LockLaunchDiagnostic(
                    status = LockLaunchDiagnostic.Status.LAUNCHED_SUCCESSFULLY,
                    reasonMessage = "Đã mở màn hình khóa ($triggerSource).",
                    timestampEpochMs = System.currentTimeMillis(),
                )
            )
            true
        } catch (e: Throwable) {
            lockRepository.recordLaunchDiagnostic(
                LockLaunchDiagnostic(
                    status = LockLaunchDiagnostic.Status.BLOCKED_BY_BACKGROUND_RESTRICTIONS,
                    reasonMessage = "Hãy bật Dịch vụ Trợ năng DigitalLock để chặn thoát ứng dụng ($triggerSource).",
                    timestampEpochMs = System.currentTimeMillis(),
                )
            )
            false
        }
    }

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
