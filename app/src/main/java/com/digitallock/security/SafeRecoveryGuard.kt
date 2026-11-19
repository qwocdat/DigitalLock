package com.digitallock.security

import android.content.Context

/**
 * Anti-lockout Safe Recovery Guard.
 *
 * Tracks rapid consecutive render/runtime failures on the Lock Screen inside Device-Protected storage.
 * If >= 3 crashes occur within 60 seconds, Safe Recovery Mode activates automatically:
 * - Disables remote UI configs & custom shaders, falling back to the built-in ultra-safe PIN UI.
 * - NEVER auto-unlocks on crash (PIN is still required).
 * - Cleared automatically after a successful PIN unlock.
 */
object SafeRecoveryGuard {
    private const val PREFS = "digitallock_safe_recovery_dp"
    private const val KEY_CRASH_COUNT = "crash_count"
    private const val KEY_FIRST_CRASH_MS = "first_crash_ms"
    private const val KEY_SAFE_MODE_ACTIVE = "safe_mode_active"

    private const val WINDOW_MS = 60_000L
    private const val CRASH_THRESHOLD = 3

    fun isSafeRecoveryMode(context: Context): Boolean {
        val prefs = dpPrefs(context)
        return prefs.getBoolean(KEY_SAFE_MODE_ACTIVE, false)
    }

    fun recordLockSurfaceFailure(context: Context): Boolean {
        val prefs = dpPrefs(context)
        val now = System.currentTimeMillis()
        val firstMs = prefs.getLong(KEY_FIRST_CRASH_MS, 0L)
        val count = if (now - firstMs > WINDOW_MS) 1 else prefs.getInt(KEY_CRASH_COUNT, 0) + 1
        val triggerSafeMode = count >= CRASH_THRESHOLD
        prefs.edit()
            .putInt(KEY_CRASH_COUNT, count)
            .putLong(KEY_FIRST_CRASH_MS, if (count == 1) now else firstMs)
            .putBoolean(KEY_SAFE_MODE_ACTIVE, triggerSafeMode || prefs.getBoolean(KEY_SAFE_MODE_ACTIVE, false))
            .commit()
        return triggerSafeMode
    }

    fun clearSafeRecoveryOnUnlock(context: Context) {
        dpPrefs(context).edit()
            .putInt(KEY_CRASH_COUNT, 0)
            .putLong(KEY_FIRST_CRASH_MS, 0L)
            .putBoolean(KEY_SAFE_MODE_ACTIVE, false)
            .commit()
    }

    private fun dpPrefs(context: Context) =
        context.applicationContext
            .createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
