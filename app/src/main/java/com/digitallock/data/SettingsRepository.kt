package com.digitallock.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LockOperationMode {
    /**
     * User disabled Android System Lock (None/Swipe) so DigitalLock provides the sole PIN screen
     * without requiring two consecutive PIN entries.
     */
    STANDALONE_SINGLE_LOCK,

    /**
     * User chose to keep Android Keyguard enabled; DigitalLock operates as an app/session lock
     * without claiming to replace Android Keyguard.
     */
    COEXIST_WITH_ANDROID_KEYGUARD,
}

data class DigitalLockSettings(
    val setupCompleted: Boolean = false,
    val lockEnabled: Boolean = false,
    val operationMode: LockOperationMode = LockOperationMode.STANDALONE_SINGLE_LOCK,
    val biometricEnabled: Boolean = false,
    val dynamicColorEnabled: Boolean = true,
    val darkThemePreference: Boolean? = null,
    val customWallpaperUri: String? = null,
    val preventScreenshotsOnLock: Boolean = true,
    val refractionIntensityScale: Float = 1.0f,
    val chromaticAberrationScale: Float = 1.0f,
)

/**
 * Device-protected settings repository so configuration is readable immediately on
 * `ACTION_LOCKED_BOOT_COMPLETED` as well as standard boot.
 */
class SettingsRepository(context: Context) {

    private val dpContext = context.applicationContext.createDeviceProtectedStorageContext()
    private val prefs = dpContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settingsFlow = MutableStateFlow(loadSettings())
    val settingsFlow: StateFlow<DigitalLockSettings> = _settingsFlow.asStateFlow()

    fun currentSettings(): DigitalLockSettings = _settingsFlow.value

    fun updateSetupCompleted(completed: Boolean, mode: LockOperationMode) {
        prefs.edit()
            .putBoolean(KEY_SETUP_COMPLETED, completed)
            .putBoolean(KEY_LOCK_ENABLED, completed)
            .putString(KEY_OPERATION_MODE, mode.name)
            .commit()
        _settingsFlow.value = loadSettings()
    }

    fun setLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOCK_ENABLED, enabled).commit()
        _settingsFlow.value = loadSettings()
    }

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).commit()
        _settingsFlow.value = loadSettings()
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
        _settingsFlow.value = loadSettings()
    }

    fun setCustomWallpaperUri(uriString: String?) {
        prefs.edit().putString(KEY_CUSTOM_WALLPAPER_URI, uriString).apply()
        _settingsFlow.value = loadSettings()
    }

    fun setPreventScreenshotsOnLock(prevent: Boolean) {
        prefs.edit().putBoolean(KEY_PREVENT_SCREENSHOTS, prevent).apply()
        _settingsFlow.value = loadSettings()
    }

    fun setOpticalTuning(refractionScale: Float, aberrationScale: Float) {
        prefs.edit()
            .putFloat(KEY_REFRACTION_SCALE, refractionScale.coerceIn(0.5f, 1.5f))
            .putFloat(KEY_ABERRATION_SCALE, aberrationScale.coerceIn(0.0f, 1.5f))
            .apply()
        _settingsFlow.value = loadSettings()
    }

    private fun loadSettings(): DigitalLockSettings {
        val modeName = prefs.getString(KEY_OPERATION_MODE, LockOperationMode.STANDALONE_SINGLE_LOCK.name)
        val mode = try {
            LockOperationMode.valueOf(modeName ?: LockOperationMode.STANDALONE_SINGLE_LOCK.name)
        } catch (_: IllegalArgumentException) {
            LockOperationMode.STANDALONE_SINGLE_LOCK
        }
        return DigitalLockSettings(
            setupCompleted = prefs.getBoolean(KEY_SETUP_COMPLETED, false),
            lockEnabled = prefs.getBoolean(KEY_LOCK_ENABLED, false),
            operationMode = mode,
            biometricEnabled = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false),
            dynamicColorEnabled = prefs.getBoolean(KEY_DYNAMIC_COLOR, true),
            customWallpaperUri = prefs.getString(KEY_CUSTOM_WALLPAPER_URI, null),
            preventScreenshotsOnLock = prefs.getBoolean(KEY_PREVENT_SCREENSHOTS, true),
            refractionIntensityScale = prefs.getFloat(KEY_REFRACTION_SCALE, 1.0f),
            chromaticAberrationScale = prefs.getFloat(KEY_ABERRATION_SCALE, 1.0f),
        )
    }

    companion object {
        private const val PREFS_NAME = "digitallock_settings_dp"
        private const val KEY_SETUP_COMPLETED = "setup_completed"
        private const val KEY_LOCK_ENABLED = "lock_enabled"
        private const val KEY_OPERATION_MODE = "operation_mode"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_CUSTOM_WALLPAPER_URI = "custom_wallpaper_uri"
        private const val KEY_PREVENT_SCREENSHOTS = "prevent_screenshots"
        private const val KEY_REFRACTION_SCALE = "refraction_scale"
        private const val KEY_ABERRATION_SCALE = "aberration_scale"
    }
}
