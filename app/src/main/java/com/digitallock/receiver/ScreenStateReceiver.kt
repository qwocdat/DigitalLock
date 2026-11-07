package com.digitallock.receiver

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.digitallock.accessibility.AccessibilityMonitor
import com.digitallock.data.LockRepository
import com.digitallock.data.SettingsRepository
import com.digitallock.lock.LockController
import com.digitallock.lock.SessionManager

class ScreenStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val settingsRepo = SettingsRepository(context)
        val settings = settingsRepo.currentSettings()
        if (!settings.setupCompleted || !settings.lockEnabled) return

        val km = context.getSystemService(KeyguardManager::class.java)
        val lockRepo = LockRepository(context)
        val controller = LockController(context, settingsRepo, lockRepo)

        when (action) {
            Intent.ACTION_SCREEN_OFF -> {
                // Only mark session locked; wait until user swipes open the lockscreen (ACTION_USER_PRESENT)
                SessionManager.lockSession()
            }

            Intent.ACTION_SCREEN_ON -> {
                SessionManager.lockSession()
                val isKeyguardStillShowing = km?.isKeyguardLocked == true
                if (isKeyguardStillShowing) {
                    // System lockscreen (clock/wallpaper) is still showing:
                    // Capture lockscreen background 1 single time if not captured yet,
                    // and wait for the user to swipe up (ACTION_USER_PRESENT) before showing PIN!
                    AccessibilityMonitor.instance?.captureLockscreenOnceIfNeeded()
                } else {
                    // System lockscreen is completely disabled (None mode), so user is already entering home screen
                    controller.requestLockScreen("ACTION_SCREEN_ON_NO_KEYGUARD")
                }
            }

            Intent.ACTION_USER_PRESENT -> {
                // User just swiped up on the lock screen to enter the home screen!
                if (SessionManager.shouldRequireLockNow()) {
                    controller.requestLockScreen("ACTION_USER_PRESENT")
                }
            }
        }
    }
}
