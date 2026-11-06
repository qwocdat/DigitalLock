package com.digitallock.security

/**
 * Manages Primary PIN and Secondary PIN lifecycle and validation rules.
 *
 * Rules enforced:
 * - Primary PIN must be 4 to 12 digits (or 4 to 12 alphanumeric characters when enabled).
 * - Confirmation must match exactly before saving.
 * - Secondary PIN is an independent fallback PIN and MUST NOT match the Primary PIN.
 */
class PinManager(private val credentialStore: CredentialStore) {

    sealed class PinSetupResult {
        data object Success : PinSetupResult()
        data class ValidationError(val message: String) : PinSetupResult()
    }

    fun isPrimaryPinConfigured(): Boolean =
        credentialStore.hasCredential(CredentialStore.CredentialSlot.PRIMARY_PIN)

    fun isSecondaryPinConfigured(): Boolean =
        credentialStore.hasCredential(CredentialStore.CredentialSlot.SECONDARY_PIN)

    fun getExpectedPinLength(): Int = credentialStore.getPrimaryPinLength()

    fun isAlphanumericMode(): Boolean = credentialStore.isPrimaryAlphanumeric()

    fun validatePinFormat(pin: String, allowAlphanumeric: Boolean): PinSetupResult {
        if (pin.length !in MIN_PIN_LENGTH..MAX_PIN_LENGTH) {
            return PinSetupResult.ValidationError("PIN must be between $MIN_PIN_LENGTH and $MAX_PIN_LENGTH characters.")
        }
        val validChars = if (allowAlphanumeric) {
            pin.all { it.isLetterOrDigit() }
        } else {
            pin.all { it.isDigit() }
        }
        if (!validChars) {
            return PinSetupResult.ValidationError(
                if (allowAlphanumeric) "Only letters and digits are allowed."
                else "PIN must contain digits (0–9) only."
            )
        }
        return PinSetupResult.Success
    }

    fun setupPrimaryPin(
        pin: String,
        confirmation: String,
        allowAlphanumeric: Boolean = false,
    ): PinSetupResult {
        val formatCheck = validatePinFormat(pin, allowAlphanumeric)
        if (formatCheck is PinSetupResult.ValidationError) return formatCheck
        if (pin != confirmation) {
            return PinSetupResult.ValidationError("PIN confirmation does not match.")
        }
        val chars = pin.toCharArray()
        return try {
            val saved = credentialStore.saveCredential(
                CredentialStore.CredentialSlot.PRIMARY_PIN,
                chars,
            )
            if (saved) {
                credentialStore.savePrimaryPinLength(pin.length, allowAlphanumeric)
                PinSetupResult.Success
            } else {
                PinSetupResult.ValidationError("Failed to persist Primary PIN in Android Keystore.")
            }
        } finally {
            chars.fill('\u0000')
        }
    }

    fun setupSecondaryPin(
        secondaryPin: String,
        confirmation: String,
        allowAlphanumeric: Boolean = false,
    ): PinSetupResult {
        val formatCheck = validatePinFormat(secondaryPin, allowAlphanumeric)
        if (formatCheck is PinSetupResult.ValidationError) return formatCheck
        if (secondaryPin != confirmation) {
            return PinSetupResult.ValidationError("Secondary PIN confirmation does not match.")
        }
        val candidateChars = secondaryPin.toCharArray()
        return try {
            if (credentialStore.verifyCredential(CredentialStore.CredentialSlot.PRIMARY_PIN, candidateChars)) {
                return PinSetupResult.ValidationError("Secondary PIN must not be identical to Primary PIN.")
            }
            val saved = credentialStore.saveCredential(
                CredentialStore.CredentialSlot.SECONDARY_PIN,
                candidateChars,
            )
            if (saved) PinSetupResult.Success
            else PinSetupResult.ValidationError("Failed to persist Secondary PIN.")
        } finally {
            candidateChars.fill('\u0000')
        }
    }

    fun verifyPrimaryPin(input: String): Boolean {
        val chars = input.toCharArray()
        return try {
            credentialStore.verifyCredential(CredentialStore.CredentialSlot.PRIMARY_PIN, chars)
        } finally {
            chars.fill('\u0000')
        }
    }

    fun verifySecondaryPin(input: String): Boolean {
        val chars = input.toCharArray()
        return try {
            credentialStore.verifyCredential(CredentialStore.CredentialSlot.SECONDARY_PIN, chars)
        } finally {
            chars.fill('\u0000')
        }
    }

    companion object {
        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 12
    }
}
