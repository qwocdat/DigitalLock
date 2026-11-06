package com.digitallock.lock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether the current interactive device session is locked or unlocked by DigitalLock.
 *
 * When the screen turns off (`Intent.ACTION_SCREEN_OFF`) or the device boots, [lockSession]
 * marks the session as requiring PIN verification before leaving [LockActivity].
 */
object SessionManager {

    private val _isSessionLocked = MutableStateFlow(false)
    val isSessionLocked: StateFlow<Boolean> = _isSessionLocked.asStateFlow()

    private val _isLockActivityVisible = MutableStateFlow(false)
    val isLockActivityVisible: StateFlow<Boolean> = _isLockActivityVisible.asStateFlow()

    @Volatile
    private var lastUnlockTimestampMs: Long = 0L

    fun lockSession() {
        _isSessionLocked.value = true
    }

    fun unlockSession() {
        _isSessionLocked.value = false
        lastUnlockTimestampMs = System.currentTimeMillis()
    }

    fun onLockActivityVisibilityChanged(visible: Boolean) {
        _isLockActivityVisible.value = visible
    }

    fun shouldRequireLockNow(): Boolean = _isSessionLocked.value
}
