package com.digitallock.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.digitallock.data.LockRepository
import com.digitallock.data.SettingsRepository
import com.digitallock.graphics.BackdropCaptureStore
import com.digitallock.lock.ActiveRecoverySheet
import com.digitallock.lock.LockActivity
import com.digitallock.lock.LockController
import com.digitallock.lock.LockUiState
import com.digitallock.lock.PinVerificationPhase
import com.digitallock.lock.SessionManager
import com.digitallock.receiver.ScreenStateReceiver
import com.digitallock.security.AuthenticationManager
import com.digitallock.security.CredentialStore
import com.digitallock.security.PinManager
import com.digitallock.security.RecoveryManager
import com.digitallock.ui.lock.LiquidGlassLockScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun onCreate() {
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}

class AccessibilityMonitor : AccessibilityService() {

    private var screenReceiver: ScreenStateReceiver? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var lockOverlayView: ComposeView? = null
    private var overlayLifecycleOwner: OverlayLifecycleOwner? = null
    private val overlayUiState = MutableStateFlow(LockUiState())
    private val overlayBitmapFlow = MutableStateFlow<Bitmap?>(null)
    private var countdownJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 40L
            flags = AccessibilityServiceInfo.DEFAULT or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        registerScreenReceiver()
        serviceScope.launch(Dispatchers.IO) {
            BackdropCaptureStore.loadPersistedBitmapIfNeeded(applicationContext)
        }
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        if (SessionManager.shouldRequireLockNow() && isLockOverlayAttached()) {
            val code = event.keyCode
            if (code == KeyEvent.KEYCODE_BACK ||
                code == KeyEvent.KEYCODE_HOME ||
                code == KeyEvent.KEYCODE_APP_SWITCH ||
                code == KeyEvent.KEYCODE_MENU
            ) {
                if (code == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    if (overlayUiState.value.activeRecoverySheet != ActiveRecoverySheet.NONE) {
                        overlayUiState.update { it.copy(activeRecoverySheet = ActiveRecoverySheet.NONE) }
                    }
                }
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val settingsRepo = SettingsRepository(this)
        val settings = settingsRepo.currentSettings()
        if (!settings.setupCompleted || !settings.lockEnabled) return

        // Do NOT show the PIN screen while the user is still on the system lockscreen (10:28 clock)!
        val km = getSystemService(KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) {
            return
        }

        if (SessionManager.shouldRequireLockNow() && !isLockOverlayAttached()) {
            showUnDismissibleLockSurface()
        }
    }

    fun isLockOverlayAttached(): Boolean = lockOverlayView != null

    /**
     * Captures the lockscreen 1 single time via AccessibilityService.takeScreenshot()
     * ONLY if no lockscreen background has been saved yet. Zero MediaProjection dialog!
     */
    fun captureLockscreenOnceIfNeeded(forceCapture: Boolean = false) {
        if (!forceCapture && BackdropCaptureStore.hasCapturedLockWallpaper.value) {
            return
        }
        // First try fast WallpaperManager read
        val fastBmp = BackdropCaptureStore.captureLockWallpaperOnceFromSystem(applicationContext)
        if (fastBmp != null && !forceCapture) {
            overlayBitmapFlow.value = fastBmp
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            mainHandler.postDelayed({
                if (isLockOverlayAttached()) return@postDelayed
                try {
                    takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        mainExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                try {
                                    val hwBuffer = screenshot.hardwareBuffer
                                    val colorSpace = screenshot.colorSpace
                                    val wrapped = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                    val swBitmap = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                                    hwBuffer.close()
                                    if (swBitmap != null) {
                                        BackdropCaptureStore.updateCapturedBitmap(applicationContext, swBitmap)
                                        overlayBitmapFlow.value = swBitmap
                                    }
                                } catch (_: Throwable) {
                                }
                            }

                            override fun onFailure(errorCode: Int) = Unit
                        },
                    )
                } catch (_: Throwable) {
                }
            }, 180L)
        }
    }

    /**
     * Attaches the LiquidGlassLockScreen directly as an un-dismissible `TYPE_ACCESSIBILITY_OVERLAY`
     * window covering the entire display including the bottom gesture bar so the user CANNOT
     * swipe up to exit to the Home Screen or close the app from Recents!
     */
    fun showUnDismissibleLockSurface() {
        mainHandler.post {
            if (lockOverlayView != null) return@post

            val wm = getSystemService(WindowManager::class.java) ?: return@post
            val credentialStore = CredentialStore(this)
            val pinManager = PinManager(credentialStore)
            val recoveryManager = RecoveryManager(credentialStore)
            val authManager = AuthenticationManager(this, credentialStore, pinManager, recoveryManager)
            val settingsRepo = SettingsRepository(this)

            val expectedLength = pinManager.getExpectedPinLength()
            val isAlpha = pinManager.isAlphanumericMode()
            val question = recoveryManager.getSecurityQuestionPrompt()
            val remainingSec = authManager.getRemainingLockoutSeconds()

            overlayUiState.value = LockUiState(
                enteredPin = "",
                targetPinLength = expectedLength,
                isAlphanumeric = isAlpha,
                phase = if (remainingSec > 0L) PinVerificationPhase.LOCKOUT_COOLDOWN else PinVerificationPhase.IDLE_ENTERING,
                statusPrompt = if (remainingSec > 0L) {
                    "Nhập sai quá nhiều lần. Thử lại sau ${remainingSec} giây"
                } else {
                    "Vuốt lên để mở bằng Sinh trắc học hoặc Nhập mật khẩu"
                },
                failedAttempts = credentialStore.getFailedAttempts(),
                lockoutRemainingSeconds = remainingSec,
                securityQuestionPrompt = question,
            )

            val owner = OverlayLifecycleOwner().also { it.onCreate() }
            overlayLifecycleOwner = owner

            val composeView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                isFocusable = true
                isFocusableInTouchMode = true
                @Suppress("DEPRECATION")
                systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                    )
                setContent {
                    LiquidGlassLockScreen(
                        uiStateFlow = overlayUiState,
                        settingsFlow = settingsRepo.settingsFlow,
                        mediaProjectionBitmapFlow = overlayBitmapFlow,
                        onDigitPressed = { digit ->
                            handleOverlayDigitInput(digit, authManager, credentialStore)
                        },
                        onBackspacePressed = {
                            val cur = overlayUiState.value
                            if (cur.phase == PinVerificationPhase.IDLE_ENTERING && cur.enteredPin.isNotEmpty()) {
                                overlayUiState.update { it.copy(enteredPin = cur.enteredPin.dropLast(1)) }
                            }
                        },
                        onBiometricRequested = {
                            // Launch lightweight LockActivity strictly for BiometricPrompt hardware dialog if enabled
                            if (settingsRepo.currentSettings().biometricEnabled) {
                                startActivity(
                                    Intent(this@AccessibilityMonitor, LockActivity::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                        putExtra(LockActivity.EXTRA_BIOMETRIC_ONLY, true)
                                    }
                                )
                            }
                        },
                        onOpenRecoverySheet = { sheet ->
                            overlayUiState.update { it.copy(activeRecoverySheet = sheet) }
                        },
                        onDismissRecoverySheet = {
                            overlayUiState.update { it.copy(activeRecoverySheet = ActiveRecoverySheet.NONE) }
                        },
                        onSubmitRecoveryCode = { code ->
                            serviceScope.launch {
                                val res = withContext(Dispatchers.Default) {
                                    authManager.authenticateWithRecoveryCode(code)
                                }
                                if (res is AuthenticationManager.AuthOutcome.Authenticated) {
                                    dismissLockSurfaceOnSuccess()
                                } else {
                                    overlayUiState.update {
                                        it.copy(statusPrompt = "Mã khôi phục không đúng.")
                                    }
                                }
                            }
                        },
                        onSubmitSecurityAnswer = { ans ->
                            serviceScope.launch {
                                val res = withContext(Dispatchers.Default) {
                                    authManager.authenticateWithSecurityQuestion(ans)
                                }
                                if (res is AuthenticationManager.AuthOutcome.Authenticated) {
                                    dismissLockSurfaceOnSuccess()
                                } else {
                                    overlayUiState.update {
                                        it.copy(statusPrompt = "Câu trả lời bảo mật không đúng.")
                                    }
                                }
                            }
                        },
                        onRequestMediaProjectionBackdrop = {
                            captureLockscreenOnceIfNeeded(forceCapture = true)
                        },
                    )
                }
            }

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            try {
                wm.addView(composeView, lp)
                lockOverlayView = composeView
                SessionManager.onLockActivityVisibilityChanged(true)
                if (remainingSec > 0L) {
                    syncOverlayLockoutCountdown(authManager)
                }
            } catch (_: Throwable) {
                owner.onDestroy()
                overlayLifecycleOwner = null
            }
        }
    }

    private fun handleOverlayDigitInput(
        digitChar: Char,
        authManager: AuthenticationManager,
        credentialStore: CredentialStore,
    ) {
        val current = overlayUiState.value
        if (current.phase != PinVerificationPhase.IDLE_ENTERING) return
        if (current.enteredPin.length >= current.targetPinLength) return

        val updatedPin = current.enteredPin + digitChar
        overlayUiState.update { it.copy(enteredPin = updatedPin) }

        if (updatedPin.length == current.targetPinLength) {
            overlayUiState.update {
                it.copy(
                    phase = PinVerificationPhase.VERIFYING,
                    statusPrompt = "Đang xác thực…",
                )
            }
            serviceScope.launch {
                val outcome = withContext(Dispatchers.Default) {
                    authManager.authenticatePin(updatedPin)
                }
                when (outcome) {
                    is AuthenticationManager.AuthOutcome.Authenticated -> {
                        dismissLockSurfaceOnSuccess()
                    }

                    is AuthenticationManager.AuthOutcome.InvalidCredential -> {
                        overlayUiState.update {
                            it.copy(
                                phase = PinVerificationPhase.ERROR_SHAKE,
                                failedAttempts = outcome.failedAttempts,
                                lockoutRemainingSeconds = outcome.lockoutRemainingSeconds,
                                statusPrompt = if (outcome.lockoutRemainingSeconds > 0L) {
                                    "Sai mã PIN. Tạm khóa ${outcome.lockoutRemainingSeconds} giây"
                                } else {
                                    "Mã PIN không đúng (Đã sai ${outcome.failedAttempts} lần)"
                                },
                            )
                        }
                        delay(420L)
                        if (outcome.lockoutRemainingSeconds > 0L) {
                            syncOverlayLockoutCountdown(authManager)
                        } else {
                            overlayUiState.update {
                                it.copy(
                                    enteredPin = "",
                                    phase = PinVerificationPhase.IDLE_ENTERING,
                                )
                            }
                        }
                    }

                    is AuthenticationManager.AuthOutcome.RateLimited -> {
                        syncOverlayLockoutCountdown(authManager)
                    }
                }
            }
        }
    }

    private fun syncOverlayLockoutCountdown(authManager: AuthenticationManager) {
        countdownJob?.cancel()
        countdownJob = serviceScope.launch {
            while (true) {
                val remaining = authManager.getRemainingLockoutSeconds()
                if (remaining <= 0L) {
                    overlayUiState.update {
                        it.copy(
                            enteredPin = "",
                            phase = PinVerificationPhase.IDLE_ENTERING,
                            lockoutRemainingSeconds = 0L,
                            statusPrompt = "Vuốt lên để mở bằng Sinh trắc học hoặc Nhập mật khẩu",
                        )
                    }
                    break
                } else {
                    overlayUiState.update {
                        it.copy(
                            enteredPin = "",
                            phase = PinVerificationPhase.LOCKOUT_COOLDOWN,
                            lockoutRemainingSeconds = remaining,
                            statusPrompt = "Thử lại sau ${remaining} giây (hoặc nhấn Khẩn cấp)",
                        )
                    }
                }
                delay(1000L)
            }
        }
    }

    fun dismissLockSurfaceOnSuccess() {
        serviceScope.launch {
            overlayUiState.update {
                it.copy(
                    phase = PinVerificationPhase.UNLOCKING_SUCCESS,
                    statusPrompt = "Đã mở khóa",
                )
            }
            delay(160L)
            SessionManager.unlockSession()
            SessionManager.onLockActivityVisibilityChanged(false)
            removeLockOverlayViewImmediate()
        }
    }

    private fun removeLockOverlayViewImmediate() {
        mainHandler.post {
            countdownJob?.cancel()
            val wm = getSystemService(WindowManager::class.java)
            lockOverlayView?.let { view ->
                try {
                    wm?.removeViewImmediate(view)
                } catch (_: Throwable) {
                }
            }
            lockOverlayView = null
            overlayLifecycleOwner?.onDestroy()
            overlayLifecycleOwner = null
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        removeLockOverlayViewImmediate()
        if (instance === this) {
            instance = null
        }
        unregisterScreenReceiver()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeLockOverlayViewImmediate()
        if (instance === this) {
            instance = null
        }
        unregisterScreenReceiver()
        serviceScope.cancel()
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
