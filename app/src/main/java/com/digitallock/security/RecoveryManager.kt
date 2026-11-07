package com.digitallock.security

import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale

class RecoveryManager(private val credentialStore: CredentialStore) {

    private val secureRandom = SecureRandom()

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
        private const val RECOVERY_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

        const val SECURITY_QUESTION_ENTROPY_WARNING =
            "Cảnh báo: Câu hỏi bảo mật có độ an toàn thấp hơn Mã khôi phục và dễ bị đoán nếu người khác biết thông tin cá nhân của bạn."

        fun normalizeSecurityAnswer(rawAnswer: String): CharArray {
            val nfkc = Normalizer.normalize(rawAnswer, Normalizer.Form.NFKC)
            val collapsed = nfkc.trim()
                .lowercase(Locale.ROOT)
                .replace(Regex("\\s+"), " ")
            return collapsed.toCharArray()
        }
    }
}
