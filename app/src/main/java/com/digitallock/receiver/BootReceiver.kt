package com.digitallock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.digitallock.data.LockRepository
import com.digitallock.data.SettingsRepository
import com.digitallock.lock.LockController
import com.digitallock.lock.LockLaunchDiagnostic
import com.digitallock.lock.SessionManager
import com.digitallock.service.MonitorService

/**
 * Restores DigitalLock configuration after device reboot (`ACTION_LOCKED_BOOT_COMPLETED`
 * and `ACTION_BOOT_COMPLETED`).
 *
 * Honest Android 10–15 behavior:
 * - Marks [SessionManager.lockSession] immediately.
 * - Starts [MonitorService] when permitted by the OS.
 * - Records `PENDING_BOOT_RESTORE` diagnostic rather than falsely claiming `LockActivity`
 *   is guaranteed to pop up immediately over the launcher without Accessibility or Device Owner.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val settingsRepo = SettingsRepository(context)
        val settings = settingsRepo.currentSettings()
        if (!settings.setupCompleted || !settings.lockEnabled) return

        SessionManager.lockSession()
        val lockRepo = LockRepository(context)
        lockRepo.recordLaunchDiagnostic(
            LockLaunchDiagnostic(
                status = LockLaunchDiagnostic.Status.PENDING_BOOT_RESTORE,
                reasonMessage = "Device rebooted ($action). Session marked locked; background service and permitted triggers restored.",
                timestampEpochMs = System.currentTimeMillis(),
            )
        )

        MonitorService.startIfEnabled(context)

        val snapshot = lockRepo.querySystemSecuritySnapshot()
        if (snapshot.isDeviceOwnerProvisioned || snapshot.isAccessibilityMonitorEnabled) {
            LockController(context, settingsRepo, lockRepo).requestLockScreen("BootReceiver:$action")
        }
    }
}
