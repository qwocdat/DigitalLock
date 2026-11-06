package com.digitallock.security

import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale

/**
 * Manages Recovery Code generation/verification and Security Question normalization/verification.
 *
 * Rules enforced:
 * - Recovery Code is generated once using CSPRNG (`XXXX-XXXX-XXXX-XXXX`), returned for one-time
 *   display/copy during setup, and immediately hashed with its own salt + Keystore AES-GCM encryption.
 * - Security Question answer is normalized (NFKC, trimmed, lowercased, collapsed whitespace) before
 *   PBKDF2-HMAC-SHA256 hashing with its own salt.
 */
class RecoveryManager(private val credentialStore: CredentialStore) {

    private val secureRandom = SecureRandom()

    /**
     * Generates a new 16-character high-entropy recovery code formatted as `XXXX-XXXX-XXXX-XXXX`,
     * stores only its salted PBKDF2-HMAC-SHA256 hash in [credentialStore], and returns the raw string
     * once so the UI can display it to the user during setup.
     */
    fun generateAndStoreOneTimeRecoveryCode(): String {
        val rawChars = CharArray(16) {
            RECOVERY_ALPHABET[secureRandom.nextInt(RECOVERY_ALPHABET.length)]
        }
        val formatted = buildString(19) {
            for (i in rawChars.indices) {
                if (i > 0 && i % 4 == 0) append('-')
                append(rawChars[i])
            }
        }
        val normalizedChars = normalizeRecoveryCode(formatted)
        try {
            credentialStore.saveCredential(
                CredentialStore.CredentialSlot.RECOVERY_CODE,
                normalizedChars,
            )
        } finally {
            rawChars.fill('\u0000')
            normalizedChars.fill('\u0000')
        }
        return formatted
    }

    fun verifyRecoveryCode(inputCode: String): Boolean {
        val normalized = normalizeRecoveryCode(inputCode)
        if (normalized.isEmpty()) return false
        return try {
            credentialStore.verifyCredential(
                CredentialStore.CredentialSlot.RECOVERY_CODE,
                normalized,
            )
        } finally {
            normalized.fill('\u0000')
        }
    }

    fun setupSecurityQuestion(question: String, answer: String): Boolean {
        val cleanQuestion = question.trim()
        val normalizedAnswer = normalizeSecurityAnswer(answer)
        if (cleanQuestion.length < 4 || normalizedAnswer.size < 2) {
            normalizedAnswer.fill('\u0000')
            return false
        }
        return try {
            val saved = credentialStore.saveCredential(
                CredentialStore.CredentialSlot.SECURITY_ANSWER,
                normalizedAnswer,
            )
            if (saved) {
                credentialStore.saveSecurityQuestionPrompt(cleanQuestion)
            }
            saved
        } finally {
            normalizedAnswer.fill('\u0000')
        }
    }

    fun verifySecurityQuestionAnswer(answer: String): Boolean {
        val normalizedAnswer = normalizeSecurityAnswer(answer)
        if (normalizedAnswer.isEmpty()) return false
        return try {
            credentialStore.verifyCredential(
                CredentialStore.CredentialSlot.SECURITY_ANSWER,
                normalizedAnswer,
            )
        } finally {
            normalizedAnswer.fill('\u0000')
        }
    }

    fun getSecurityQuestionPrompt(): String? = credentialStore.getSecurityQuestionPrompt()

    fun hasRecoveryCode(): Boolean =
        credentialStore.hasCredential(CredentialStore.CredentialSlot.RECOVERY_CODE)

    fun hasSecurityQuestion(): Boolean =
        credentialStore.hasCredential(CredentialStore.CredentialSlot.SECURITY_ANSWER) &&
            !credentialStore.getSecurityQuestionPrompt().isNullOrBlank()

    private fun normalizeRecoveryCode(input: String): CharArray {
        val cleaned = input.uppercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }
        return cleaned.toCharArray()
    }

    companion object {
        // Unambiguous uppercase alphanumeric alphabet (excludes 0/O, 1/I/L)
        private const val RECOVERY_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

        const val SECURITY_QUESTION_ENTROPY_WARNING =
            "Security Questions have lower cryptographic entropy than Recovery Codes and can be guessed by people who know your personal history. Use a high-entropy phrase."

        fun normalizeSecurityAnswer(rawAnswer: String): CharArray {
            val nfkc = Normalizer.normalize(rawAnswer, Normalizer.Form.NFKC)
            val collapsed = nfkc.trim()
                .lowercase(Locale.ROOT)
                .replace(Regex("\\s+"), " ")
            return collapsed.toCharArray()
        }
    }
}
