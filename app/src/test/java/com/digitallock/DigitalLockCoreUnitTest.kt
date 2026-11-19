package com.digitallock

import com.digitallock.remote.PinKeyShape
import com.digitallock.remote.RemoteUiConfig
import com.digitallock.security.CredentialStore
import com.digitallock.security.NativeSecurityBridge
import com.digitallock.security.RecoveryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DigitalLockCoreUnitTest {

    @Test
    fun backoffCurveNeverBricksUserAndCapsAt15Minutes() {
        assertEquals(0L, CredentialStore.computeBackoffDurationMs(1))
        assertEquals(0L, CredentialStore.computeBackoffDurationMs(4))
        assertEquals(30_000L, CredentialStore.computeBackoffDurationMs(5))
        assertEquals(60_000L, CredentialStore.computeBackoffDurationMs(6))
        assertEquals(300_000L, CredentialStore.computeBackoffDurationMs(7))
        assertEquals(900_000L, CredentialStore.computeBackoffDurationMs(8))
        assertEquals(900_000L, CredentialStore.computeBackoffDurationMs(50))
    }

    @Test
    fun securityAnswerNormalizationIsDeterministic() {
        val a = String(RecoveryManager.normalizeSecurityAnswer("  Hà   Nội  "))
        val b = String(RecoveryManager.normalizeSecurityAnswer("hà nội"))
        assertEquals(a, b)
    }

    @Test
    fun manifestSchemaRejectsPathTraversalAndExecutables() {
        val badPathJson = """
            {
              "schema_version": 1,
              "version_code": 2,
              "version_name": "2.0.0",
              "min_app_version_code": 1,
              "lockscreen": {},
              "pin_screen": {},
              "resources": [
                {"id":"w1","type":"wallpaper","path":"../private/key.pem","sha256":"${"a".repeat(64)}","size":1024}
              ]
            }
        """.trimIndent()
        val resPath = NativeSecurityBridge.validateManifestSchema(badPathJson)
        assertTrue(resPath is NativeSecurityBridge.ManifestValidationResult.Invalid)

        val badDexJson = """
            {
              "schema_version": 1,
              "version_code": 2,
              "version_name": "2.0.0",
              "min_app_version_code": 1,
              "lockscreen": {},
              "pin_screen": {},
              "resources": [
                {"id":"p1","type":"config","path":"configs/payload.dex","sha256":"${"b".repeat(64)}","size":1024}
              ]
            }
        """.trimIndent()
        val resDex = NativeSecurityBridge.validateManifestSchema(badDexJson)
        assertTrue(resDex is NativeSecurityBridge.ManifestValidationResult.Invalid)
    }

    @Test
    fun remoteUiConfigClampsBoundsAndSanitizesColors() {
        val validJson = """
            {
              "schema_version": 1,
              "version_code": 5,
              "version_name": "2.1.0",
              "min_app_version_code": 1,
              "lockscreen": {
                "status_prompt_text": "Nhập mã PIN của bạn",
                "prompt_color_hex": "INVALID_COLOR",
                "unlock_fade_duration_ms": 99999
              },
              "pin_screen": {
                "key_size_dp": 200.0,
                "key_shape": "SQUIRCLE",
                "refraction_scale": 9.9,
                "allowlisted_shader_id": "malicious_shader"
              },
              "resources": []
            }
        """.trimIndent()
        val cfg = RemoteUiConfig.parseFromVerifiedManifestJson(validJson)
        assertEquals(5L, cfg.versionCode)
        assertEquals("#F0FFFFFF", cfg.lockScreen.promptColorHex)
        assertEquals(600, cfg.lockScreen.unlockFadeDurationMs)
        assertEquals(96f, cfg.pinScreen.keySizeDp, 0.01f)
        assertEquals(PinKeyShape.SQUIRCLE, cfg.pinScreen.keyShape)
        assertEquals(1.5f, cfg.pinScreen.refractionScale, 0.01f)
        assertEquals("liquid_glass_v2", cfg.pinScreen.allowlistedShaderId)
        assertFalse(cfg.versionName.isBlank())
    }
}
