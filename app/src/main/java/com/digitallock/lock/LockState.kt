package com.digitallock.lock

enum class PinVerificationPhase {
    IDLE_ENTERING,
    VERIFYING,
    ERROR_SHAKE,
    LOCKOUT_COOLDOWN,
    UNLOCKING_SUCCESS,
}

enum class ActiveRecoverySheet {
    NONE,
    RECOVERY_CODE,
    SECURITY_QUESTION,
}

data class LockUiState(
    val enteredPin: String = "",
    val targetPinLength: Int = 6,
    val isAlphanumeric: Boolean = false,
    val phase: PinVerificationPhase = PinVerificationPhase.IDLE_ENTERING,
    val statusPrompt: String = "Vuốt lên để mở bằng Sinh trắc học hoặc Nhập PIN",
    val failedAttempts: Int = 0,
    val lockoutRemainingSeconds: Long = 0L,
    val activeRecoverySheet: ActiveRecoverySheet = ActiveRecoverySheet.NONE,
    val securityQuestionPrompt: String? = null,
    val isDeviceOwnerLockTaskActive: Boolean = false,
)

data class LockLaunchDiagnostic(
    val status: Status,
    val reasonMessage: String,
    val timestampEpochMs: Long,
) {
    enum class Status {
        IDLE,
        LAUNCHED_SUCCESSFULLY,
        BLOCKED_BY_BACKGROUND_RESTRICTIONS,
        PENDING_BOOT_RESTORE,
    }
}
