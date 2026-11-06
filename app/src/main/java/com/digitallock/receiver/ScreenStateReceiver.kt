package com.digitallock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.digitallock.data.LockRepository
import com.digitallock.data.SettingsRepository
import com.digitallock.lock.LockController
import com.digitallock.lock.SessionManager

/**
 * Handles `ACTION_SCREEN_OFF`, `ACTION_SCREEN_ON`, and `ACTION_USER_PRESENT` events.
 *
 * When the screen turns off or on while DigitalLock is enabled, marks the session as locked
 * and requests [LockController] to launch `LockActivity` within Android platform rules.
 */
class ScreenStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val settingsRepo = SettingsRepository(context)
        val settings = settingsRepo.currentSettings()
        if (!settings.setupCompleted || !settings.lockEnabled) return

        val lockRepo = LockRepository(context)
        val controller = LockController(context, settingsRepo, lockRepo)

        when (action) {
            Intent.ACTION_SCREEN_OFF -> {
                SessionManager.lockSession()
                controller.requestLockScreen("ACTION_SCREEN_OFF")
            }

            Intent.ACTION_SCREEN_ON -> {
                if (SessionManager.shouldRequireLockNow()) {
                    controller.requestLockScreen("ACTION_SCREEN_ON")
                }
            }

            Intent.ACTION_USER_PRESENT -> {
                if (SessionManager.shouldRequireLockNow()) {
                    controller.requestLockScreen("ACTION_USER_PRESENT")
                }
            }
        }
    }
}
