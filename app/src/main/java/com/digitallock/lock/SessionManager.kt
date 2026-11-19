package com.digitallock.lock

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether the current interactive device session is locked or unlocked by DigitalLock.
 *
 * Persists lock state inside Device-Protected storage (`digitallock_session_dp`) so an app crash
 * or process restart NEVER silently unlocks an active lock session.
 */
object SessionManager {

    private const val PREFS_NAME = "digitallock_session_dp"
    private const val KEY_IS_LOCKED = "session_locked"
    private const val KEY_LAST_UNLOCK_MS = "last_unlock_ms"

    @Volatile
    private var dpPrefs: SharedPreferences? = null

    private val _isSessionLocked = MutableStateFlow(false)
    val isSessionLocked: StateFlow<Boolean> = _isSessionLocked.asStateFlow()

    private val _isLockActivityVisible = MutableStateFlow(false)
    val isLockActivityVisible: StateFlow<Boolean> = _isLockActivityVisible.asStateFlow()

    @Volatile
    private var lastUnlockTimestampMs: Long = 0L

    fun init(context: Context) {
        if (dpPrefs == null) {
            synchronized(this) {
                if (dpPrefs == null) {
                    val prefs = context.applicationContext
                        .createDeviceProtectedStorageContext()
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    dpPrefs = prefs
                    _isSessionLocked.value = prefs.getBoolean(KEY_IS_LOCKED, false)
                    lastUnlockTimestampMs = prefs.getLong(KEY_LAST_UNLOCK_MS, 0L)
                }
            }
        }
    }

    fun lockSession() {
        _isSessionLocked.value = true
        dpPrefs?.edit()?.putBoolean(KEY_IS_LOCKED, true)?.commit()
    }

    fun unlockSession() {
        val now = System.currentTimeMillis()
        _isSessionLocked.value = false
        lastUnlockTimestampMs = now
        dpPrefs?.edit()
            ?.putBoolean(KEY_IS_LOCKED, false)
            ?.putLong(KEY_LAST_UNLOCK_MS, now)
            ?.commit()
    }

    fun onLockActivityVisibilityChanged(visible: Boolean) {
        _isLockActivityVisible.value = visible
    }

    fun shouldRequireLockNow(): Boolean = _isSessionLocked.value
}
