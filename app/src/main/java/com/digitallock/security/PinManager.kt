package com.digitallock.security

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
            return PinSetupResult.ValidationError("Mã PIN phải từ $MIN_PIN_LENGTH đến $MAX_PIN_LENGTH ký tự.")
        }
        val validChars = if (allowAlphanumeric) {
            pin.all { it.isLetterOrDigit() }
        } else {
            pin.all { it.isDigit() }
        }
        if (!validChars) {
            return PinSetupResult.ValidationError(
                if (allowAlphanumeric) "Chỉ cho phép nhập chữ cái và chữ số."
                else "Mã PIN chỉ được phép chứa chữ số (0–9)."
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
            return PinSetupResult.ValidationError("Mã PIN xác nhận không khớp.")
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
                PinSetupResult.ValidationError("Không thể lưu mã PIN vào Android Keystore.")
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
            return PinSetupResult.ValidationError("Mã PIN phụ xác nhận không khớp.")
        }
        val candidateChars = secondaryPin.toCharArray()
        return try {
            if (credentialStore.verifyCredential(CredentialStore.CredentialSlot.PRIMARY_PIN, candidateChars)) {
                return PinSetupResult.ValidationError("Mã PIN phụ không được trùng với mã PIN chính.")
            }
            val saved = credentialStore.saveCredential(
                CredentialStore.CredentialSlot.SECONDARY_PIN,
                candidateChars,
            )
            if (saved) PinSetupResult.Success
            else PinSetupResult.ValidationError("Không thể lưu mã PIN phụ.")
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
