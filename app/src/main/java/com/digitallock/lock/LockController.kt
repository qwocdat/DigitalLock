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

        if (SessionManager.isLockActivityVisible.value) {
            return true
        }

        // Prefer launching from AccessibilityMonitor service instance if connected,
        // as AccessibilityService has direct OS exemption to start Activities from background.
        val launchContext: Context = AccessibilityMonitor.instance ?: context

        return try {
            val intent = Intent(launchContext, LockActivity::class.java).apply {
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

            launchContext.startActivity(intent, options)
            lockRepository.recordLaunchDiagnostic(
                LockLaunchDiagnostic(
                    status = LockLaunchDiagnostic.Status.LAUNCHED_SUCCESSFULLY,
                    reasonMessage = "Đã kích hoạt màn hình khóa ($triggerSource).",
                    timestampEpochMs = System.currentTimeMillis(),
                )
            )
            true
        } catch (e: Throwable) {
            lockRepository.recordLaunchDiagnostic(
                LockLaunchDiagnostic(
                    status = LockLaunchDiagnostic.Status.BLOCKED_BY_BACKGROUND_RESTRICTIONS,
                    reasonMessage = "Cần cấp quyền Hiển thị trên ứng dụng khác & Trợ năng để tự mở khóa ($triggerSource).",
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
