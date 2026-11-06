package com.digitallock.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.digitallock.data.LockRepository
import com.digitallock.data.SettingsRepository
import com.digitallock.lock.LockActivity
import com.digitallock.lock.LockController
import com.digitallock.lock.SessionManager
import com.digitallock.receiver.ScreenStateReceiver

/**
 * Optional, user-consented AccessibilityService for DigitalLock.
 *
 * Strict privacy & security contract:
 * - `canRetrieveWindowContent` is `false` (never reads text fields, passwords, or personal content).
 * - Only observes top-level window state transitions (`TYPE_WINDOW_STATE_CHANGED`) and screen
 *   on/off events to enforce an active locked session when the user swipes Home or switches apps
 *   before completing PIN verification.
 */
class AccessibilityMonitor : AccessibilityService() {

    private var screenReceiver: ScreenStateReceiver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 150L
            flags = AccessibilityServiceInfo.DEFAULT
        }
        registerScreenReceiver()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkgName = event.packageName?.toString() ?: return

        // Ignore our own LockActivity or system permission dialogs/biometric prompt overlays
        if (pkgName == packageName ||
            pkgName == "com.android.systemui" ||
            pkgName.contains("biometric", ignoreCase = true)
        ) {
            return
        }

        val settingsRepo = SettingsRepository(this)
        val settings = settingsRepo.currentSettings()
        if (!settings.setupCompleted || !settings.lockEnabled) return

        if (SessionManager.shouldRequireLockNow()) {
            val lockRepo = LockRepository(this)
            LockController(this, settingsRepo, lockRepo)
                .requestLockScreen("AccessibilityWindowStateChanged")
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        unregisterScreenReceiver()
        return super.onUnbind(intent)
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = ScreenStateReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        screenReceiver = receiver
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
            }
            screenReceiver = null
        }
    }
}
