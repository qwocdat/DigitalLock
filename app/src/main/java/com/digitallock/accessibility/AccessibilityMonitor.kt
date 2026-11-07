package com.digitallock.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.digitallock.data.LockRepository
import com.digitallock.data.SettingsRepository
import com.digitallock.lock.LockController
import com.digitallock.lock.SessionManager
import com.digitallock.receiver.ScreenStateReceiver

class AccessibilityMonitor : AccessibilityService() {

    private var screenReceiver: ScreenStateReceiver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 80L
            flags = AccessibilityServiceInfo.DEFAULT
        }
        registerScreenReceiver()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkgName = event?.packageName?.toString() ?: return

        if (pkgName == packageName ||
            pkgName == "com.android.systemui" ||
            pkgName.contains("biometric", ignoreCase = true) ||
            pkgName.contains("permissioncontroller", ignoreCase = true)
        ) {
            return
        }

        val settingsRepo = SettingsRepository(this)
        val settings = settingsRepo.currentSettings()
        if (!settings.setupCompleted || !settings.lockEnabled) return

        if (SessionManager.shouldRequireLockNow() && !SessionManager.isLockActivityVisible.value) {
            val lockRepo = LockRepository(this)
            LockController(this, settingsRepo, lockRepo)
                .requestLockScreen("AccessibilityWindowStateChanged")
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            instance = null
        }
        unregisterScreenReceiver()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        unregisterScreenReceiver()
        super.onDestroy()
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

    companion object {
        @Volatile
        var instance: AccessibilityMonitor? = null
            private set
    }
}
