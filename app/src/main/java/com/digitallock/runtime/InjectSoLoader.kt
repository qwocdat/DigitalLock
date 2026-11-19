package com.digitallock.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class InjectSoPayload(
    val structVersion: Int,
    val minAppVersion: Int,
    val defaultRefraction: Float,
    val defaultAberration: Float,
    val defaultFresnel: Float,
    val flags: Int,
) {
    val isLockEngineEnabled: Boolean get() = (flags and 0x1) != 0
    val isGlassFxEnabled: Boolean get() = (flags and 0x2) != 0
}

/**
 * Parses and validates the downloaded ELF64 AArch64 `inject.so` binary from the PHP Backend
 * and extracts the embedded `DLINJ001` configuration record safely in memory.
 */
object InjectSoLoader {

    private val ELF_MAGIC = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
    private val RECORD_MAGIC = "DLINJ001".toByteArray(Charsets.US_ASCII)
    private const val RECORD_SIZE = 28

    fun parseAndValidate(soBytes: ByteArray): InjectSoPayload? {
        if (soBytes.size < 64 + RECORD_SIZE) return null
        // Verify ELF64 little-endian AArch64 (e_machine == 183 / 0xB7)
        for (i in 0 until 4) {
            if (soBytes[i] != ELF_MAGIC[i]) return null
        }
        if (soBytes[4].toInt() != 2) return null // ELFCLASS64

        val idx = findSubarray(soBytes, RECORD_MAGIC)
        if (idx < 0 || idx + RECORD_SIZE > soBytes.size) return null

        val buf = ByteBuffer.wrap(soBytes, idx + 8, RECORD_SIZE - 8)
            .order(ByteOrder.LITTLE_ENDIAN)
        val structVer = buf.int
        val minAppVer = buf.int
        val refraction = buf.float.coerceIn(0.5f, 1.5f)
        val aberration = buf.float.coerceIn(0.0f, 1.5f)
        val fresnel = buf.float.coerceIn(0.0f, 1.0f)
        val flags = buf.int

        if (structVer < 1 || minAppVer < 1) return null
        return InjectSoPayload(
            structVersion = structVer,
            minAppVersion = minAppVer,
            defaultRefraction = refraction,
            defaultAberration = aberration,
            defaultFresnel = fresnel,
            flags = flags,
        )
    }

    private fun findSubarray(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
