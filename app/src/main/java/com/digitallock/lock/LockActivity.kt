package com.digitallock.lock

import android.app.admin.DevicePolicyManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.digitallock.data.LockRepository
import com.digitallock.data.SettingsRepository
import com.digitallock.graphics.LockBackgroundRenderer
import com.digitallock.security.AuthenticationManager
import com.digitallock.security.CredentialStore
import com.digitallock.security.PinManager
import com.digitallock.security.RecoveryManager
import com.digitallock.ui.lock.LiquidGlassLockScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LockActivity : FragmentActivity() {

    private lateinit var credentialStore: CredentialStore
    private lateinit var pinManager: PinManager
    private lateinit var recoveryManager: RecoveryManager
    private lateinit var authManager: AuthenticationManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var lockRepository: LockRepository
    private lateinit var lockController: LockController

    private val _uiState = MutableStateFlow(LockUiState())
    val uiState: StateFlow<LockUiState> = _uiState.asStateFlow()

    private val _mediaProjectionBitmap = MutableStateFlow<Bitmap?>(null)
    private var countdownJob: Job? = null
    private var isLockTaskActive = false

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val projection = mpm?.getMediaProjection(result.resultCode, result.data!!)
            if (projection != null) {
                val metrics = resources.displayMetrics
                LockBackgroundRenderer.captureSingleFrameFromMediaProjection(
                    mediaProjection = projection,
                    width = metrics.widthPixels,
                    height = metrics.heightPixels,
                    densityDpi = metrics.densityDpi,
                ) { capturedBitmap ->
                    _mediaProjectionBitmap.value = capturedBitmap
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setShowWhenLocked(true)
        setTurnScreenOn(true)

        credentialStore = CredentialStore(this)
        pinManager = PinManager(credentialStore)
        recoveryManager = RecoveryManager(credentialStore)
        authManager = AuthenticationManager(this, credentialStore, pinManager, recoveryManager)
        settingsRepository = SettingsRepository(this)
        lockRepository = LockRepository(this)
        lockController = LockController(this, settingsRepository, lockRepository)

        val settings = settingsRepository.currentSettings()
        if (settings.preventScreenshotsOnLock) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }

        configureImmersiveFullscreen()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (_uiState.value.activeRecoverySheet != ActiveRecoverySheet.NONE) {
                        _uiState.update { it.copy(activeRecoverySheet = ActiveRecoverySheet.NONE) }
                    }
                }
            },
        )

        SessionManager.lockSession()
        SessionManager.onLockActivityVisibilityChanged(true)
        initializeLockUiState()

        setContent {
            LiquidGlassLockScreen(
                uiStateFlow = uiState,
                settingsFlow = settingsRepository.settingsFlow,
                mediaProjectionBitmapFlow = _mediaProjectionBitmap,
                onDigitPressed = ::onDigitInput,
                onBackspacePressed = ::onBackspaceInput,
                onBiometricRequested = ::triggerBiometricUnlock,
                onOpenRecoverySheet = ::openRecoverySheet,
                onDismissRecoverySheet = { openRecoverySheet(ActiveRecoverySheet.NONE) },
                onSubmitRecoveryCode = ::submitRecoveryCode,
                onSubmitSecurityAnswer = ::submitSecurityQuestionAnswer,
                onRequestMediaProjectionBackdrop = ::requestConsentingMediaProjectionCapture,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        SessionManager.onLockActivityVisibilityChanged(true)
        maybeStartDeviceOwnerLockTask()
    }

    override fun onResume() {
        super.onResume()
        configureImmersiveFullscreen()
        syncLockoutCountdown()
    }

    override fun onStop() {
        super.onStop()
        SessionManager.onLockActivityVisibilityChanged(false)
    }

    override fun onDestroy() {
        SessionManager.onLockActivityVisibilityChanged(false)
        countdownJob?.cancel()
        _mediaProjectionBitmap.value = null
        super.onDestroy()
    }

    private fun initializeLockUiState() {
        val expectedLength = pinManager.getExpectedPinLength()
        val isAlpha = pinManager.isAlphanumericMode()
        val question = recoveryManager.getSecurityQuestionPrompt()
        val remainingSec = authManager.getRemainingLockoutSeconds()
        _uiState.value = LockUiState(
            enteredPin = "",
            targetPinLength = expectedLength,
            isAlphanumeric = isAlpha,
            phase = if (remainingSec > 0L) PinVerificationPhase.LOCKOUT_COOLDOWN else PinVerificationPhase.IDLE_ENTERING,
            statusPrompt = if (remainingSec > 0L) {
                "Nhập sai quá nhiều lần. Thử lại sau ${remainingSec} giây"
            } else {
                "Vuốt lên để mở bằng Sinh trắc học hoặc Nhập PIN"
            },
            failedAttempts = credentialStore.getFailedAttempts(),
            lockoutRemainingSeconds = remainingSec,
            securityQuestionPrompt = question,
            isDeviceOwnerLockTaskActive = isLockTaskActive,
        )
        if (remainingSec > 0L) {
            syncLockoutCountdown()
        }
    }

    private fun onDigitInput(digitChar: Char) {
        val current = _uiState.value
        if (current.phase != PinVerificationPhase.IDLE_ENTERING) return
        if (current.enteredPin.length >= current.targetPinLength) return

        val updatedPin = current.enteredPin + digitChar
        _uiState.update { it.copy(enteredPin = updatedPin) }

        if (updatedPin.length == current.targetPinLength) {
            verifyEnteredPin(updatedPin)
        }
    }

    private fun onBackspaceInput() {
        val current = _uiState.value
        if (current.phase != PinVerificationPhase.IDLE_ENTERING) return
        if (current.enteredPin.isEmpty()) return
        _uiState.update { it.copy(enteredPin = current.enteredPin.dropLast(1)) }
    }

    private fun verifyEnteredPin(candidatePin: String) {
        _uiState.update {
            it.copy(
                phase = PinVerificationPhase.VERIFYING,
                statusPrompt = "Đang xác thực mã PIN…",
            )
        }
        lifecycleScope.launch {
            when (val outcome = authManager.authenticatePin(candidatePin)) {
                is AuthenticationManager.AuthOutcome.Authenticated -> {
                    completeUnlockTransition()
                }

                is AuthenticationManager.AuthOutcome.InvalidCredential -> {
                    _uiState.update {
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
                    delay(480L)
                    if (outcome.lockoutRemainingSeconds > 0L) {
                        syncLockoutCountdown()
                    } else {
                        _uiState.update {
                            it.copy(
                                enteredPin = "",
                                phase = PinVerificationPhase.IDLE_ENTERING,
                            )
                        }
                    }
                }

                is AuthenticationManager.AuthOutcome.RateLimited -> {
                    syncLockoutCountdown()
                }
            }
        }
    }

    private fun triggerBiometricUnlock() {
        val settings = settingsRepository.currentSettings()
        if (!settings.biometricEnabled) {
            _uiState.update { it.copy(statusPrompt = "Mở khóa sinh trắc học chưa được bật trong Cài đặt") }
            return
        }
        authManager.triggerBiometricPrompt(
            activity = this,
            onSuccess = {
                lifecycleScope.launch { completeUnlockTransition() }
            },
            onErrorOrCancel = { errMsg ->
                _uiState.update { it.copy(statusPrompt = errMsg) }
            },
        )
    }

    private fun openRecoverySheet(sheet: ActiveRecoverySheet) {
        _uiState.update { it.copy(activeRecoverySheet = sheet) }
    }

    private fun submitRecoveryCode(code: String) {
        when (authManager.authenticateWithRecoveryCode(code)) {
            is AuthenticationManager.AuthOutcome.Authenticated -> {
                lifecycleScope.launch {
                    _uiState.update { it.copy(activeRecoverySheet = ActiveRecoverySheet.NONE) }
                    completeUnlockTransition()
                }
            }
            else -> {
                _uiState.update {
                    it.copy(statusPrompt = "Mã khôi phục không hợp lệ. Vui lòng kiểm tra lại.")
                }
            }
        }
    }

    private fun submitSecurityQuestionAnswer(answer: String) {
        when (authManager.authenticateWithSecurityQuestion(answer)) {
            is AuthenticationManager.AuthOutcome.Authenticated -> {
                lifecycleScope.launch {
                    _uiState.update { it.copy(activeRecoverySheet = ActiveRecoverySheet.NONE) }
                    completeUnlockTransition()
                }
            }
            else -> {
                _uiState.update {
                    it.copy(statusPrompt = "Câu trả lời bảo mật không chính xác.")
                }
            }
        }
    }

    private suspend fun completeUnlockTransition() {
        _uiState.update {
            it.copy(
                phase = PinVerificationPhase.UNLOCKING_SUCCESS,
                statusPrompt = "Đã mở khóa",
            )
        }
        delay(320L)
        SessionManager.unlockSession()
        if (isLockTaskActive) {
            try {
                stopLockTask()
                isLockTaskActive = false
            } catch (_: Throwable) {
            }
        }
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private fun syncLockoutCountdown() {
        countdownJob?.cancel()
        countdownJob = lifecycleScope.launch {
            while (true) {
                val remaining = authManager.getRemainingLockoutSeconds()
                if (remaining <= 0L) {
                    _uiState.update {
                        it.copy(
                            enteredPin = "",
                            phase = PinVerificationPhase.IDLE_ENTERING,
                            lockoutRemainingSeconds = 0L,
                            statusPrompt = "Vuốt lên để mở bằng Sinh trắc học hoặc Nhập PIN",
                        )
                    }
                    break
                } else {
                    _uiState.update {
                        it.copy(
                            enteredPin = "",
                            phase = PinVerificationPhase.LOCKOUT_COOLDOWN,
                            lockoutRemainingSeconds = remaining,
                            statusPrompt = "Thử lại sau ${remaining} giây (hoặc nhấn Khôi phục)",
                        )
                    }
                }
                delay(1000L)
            }
        }
    }

    private fun maybeStartDeviceOwnerLockTask() {
        val dpm = getSystemService(DevicePolicyManager::class.java) ?: return
        if (dpm.isDeviceOwnerApp(packageName)) {
            lockController.configureDeviceOwnerLockTaskIfProvisioned()
            if (dpm.isLockTaskPermitted(packageName) && !isLockTaskActive) {
                try {
                    startLockTask()
                    isLockTaskActive = true
                    _uiState.update { it.copy(isDeviceOwnerLockTaskActive = true) }
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun requestConsentingMediaProjectionCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java) ?: return
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun configureImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
