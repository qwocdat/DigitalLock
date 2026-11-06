package com.digitallock.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Coordinates all DigitalLock authentication and recovery pathways:
 * 1. Primary PIN
 * 2. Secondary PIN (independent fallback)
 * 3. Recovery Code (one-time displayed CSPRNG token)
 * 4. Security Question (normalized & salted PBKDF2 hash)
 * 5. Strong Biometric Authentication (`BiometricPrompt` with `BIOMETRIC_STRONG`)
 *
 * Enforces persistent rate-limiting with progressive exponential cooldowns while always
 * keeping recovery pathways accessible so the user is never permanently bricked.
 */
class AuthenticationManager(
    private val context: Context,
    val credentialStore: CredentialStore,
    val pinManager: PinManager,
    val recoveryManager: RecoveryManager,
) {

    sealed class AuthOutcome {
        data class Authenticated(val method: AuthMethod) : AuthOutcome()
        data class InvalidCredential(
            val failedAttempts: Int,
            val lockoutRemainingSeconds: Long,
        ) : AuthOutcome()
        data class RateLimited(val remainingSeconds: Long) : AuthOutcome()
    }

    enum class AuthMethod {
        PRIMARY_PIN,
        SECONDARY_PIN,
        RECOVERY_CODE,
        SECURITY_QUESTION,
        BIOMETRIC_STRONG,
    }

    fun getRemainingLockoutSeconds(nowEpochMs: Long = System.currentTimeMillis()): Long {
        val lockoutUntil = credentialStore.getLockoutUntilEpochMs()
        if (lockoutUntil <= nowEpochMs) return 0L
        return ((lockoutUntil - nowEpochMs + 999L) / 1000L).coerceAtLeast(1L)
    }

    /**
     * Authenticates a PIN entry against either Primary PIN or Secondary PIN.
     */
    fun authenticatePin(
        candidatePin: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): AuthOutcome {
        val remainingLockout = getRemainingLockoutSeconds(nowEpochMs)
        if (remainingLockout > 0L) {
            return AuthOutcome.RateLimited(remainingLockout)
        }

        if (pinManager.verifyPrimaryPin(candidatePin)) {
            credentialStore.resetFailedAttempts()
            return AuthOutcome.Authenticated(AuthMethod.PRIMARY_PIN)
        }

        if (pinManager.isSecondaryPinConfigured() && pinManager.verifySecondaryPin(candidatePin)) {
            credentialStore.resetFailedAttempts()
            return AuthOutcome.Authenticated(AuthMethod.SECONDARY_PIN)
        }

        val lockoutUntil = credentialStore.recordFailedAttempt(nowEpochMs)
        val cooldownSec = if (lockoutUntil > nowEpochMs) {
            ((lockoutUntil - nowEpochMs + 999L) / 1000L)
        } else {
            0L
        }
        return AuthOutcome.InvalidCredential(
            failedAttempts = credentialStore.getFailedAttempts(),
            lockoutRemainingSeconds = cooldownSec,
        )
    }

    /**
     * Recovery pathways bypass standard PIN cooldowns so that a user locked out by forgotten PIN
     * attempts can still recover access using their high-entropy Recovery Code or Security Question.
     */
    fun authenticateWithRecoveryCode(code: String): AuthOutcome {
        return if (recoveryManager.verifyRecoveryCode(code)) {
            credentialStore.resetFailedAttempts()
            AuthOutcome.Authenticated(AuthMethod.RECOVERY_CODE)
        } else {
            val lockoutUntil = credentialStore.recordFailedAttempt(System.currentTimeMillis())
            val rem = ((lockoutUntil - System.currentTimeMillis()).coerceAtLeast(0L)) / 1000L
            AuthOutcome.InvalidCredential(credentialStore.getFailedAttempts(), rem)
        }
    }

    fun authenticateWithSecurityQuestion(answer: String): AuthOutcome {
        return if (recoveryManager.verifySecurityQuestionAnswer(answer)) {
            credentialStore.resetFailedAttempts()
            AuthOutcome.Authenticated(AuthMethod.SECURITY_QUESTION)
        } else {
            val lockoutUntil = credentialStore.recordFailedAttempt(System.currentTimeMillis())
            val rem = ((lockoutUntil - System.currentTimeMillis()).coerceAtLeast(0L)) / 1000L
            AuthOutcome.InvalidCredential(credentialStore.getFailedAttempts(), rem)
        }
    }

    fun isStrongBiometricAvailable(): Boolean {
        val bm = BiometricManager.from(context)
        return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun triggerBiometricPrompt(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onErrorOrCancel: (String) -> Unit,
    ) {
        if (!isStrongBiometricAvailable()) {
            onErrorOrCancel("Class 3 (Strong) Biometrics not enrolled or unavailable on this device.")
            return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    credentialStore.resetFailedAttempts()
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onErrorOrCancel(errString.toString())
                }
            },
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("DigitalLock Biometric Unlock")
            .setSubtitle("Verify your identity to unlock DigitalLock")
            .setNegativeButtonText("Enter Passcode")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()

        prompt.authenticate(promptInfo)
    }
}
