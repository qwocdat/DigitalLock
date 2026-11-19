package com.digitallock.remote

import androidx.compose.runtime.Immutable
import org.json.JSONObject

enum class PinKeyShape {
    CIRCLE,
    ROUNDED_RECT,
    SQUIRCLE,
}

@Immutable
data class RemoteResourceEntry(
    val id: String,
    val type: String,
    val path: String,
    val sha256: String,
    val size: Long,
)

@Immutable
data class RemoteLockScreenConfig(
    val showClockWidget: Boolean = false,
    val statusPromptText: String = "Vuốt lên để mở bằng Sinh trắc học hoặc Nhập mật khẩu",
    val promptColorHex: String = "#F0FFFFFF",
    val errorPromptColorHex: String = "#FFFFB4AB",
    val wallpaperResourceId: String? = null,
    val unlockFadeDurationMs: Int = 200,
    val shakeDurationMs: Int = 42,
)

@Immutable
data class RemotePinScreenConfig(
    val keySizeDp: Float = 82f,
    val keyHorizontalSpacingDp: Float = 24f,
    val keyVerticalSpacingDp: Float = 16f,
    val keyShape: PinKeyShape = PinKeyShape.CIRCLE,
    val keyCornerRadiusDp: Float = 24f,
    val digitFontSizeSp: Float = 34f,
    val letterFontSizeSp: Float = 11f,
    val digitColorHex: String = "#FFFFFFFF",
    val letterColorHex: String = "#CCFFFFFF",
    val dotSizeDp: Float = 13f,
    val dotSpacingDp: Float = 20f,
    val dotFilledColorHex: String = "#FFFFFFFF",
    val dotEmptyBorderColorHex: String = "#B3FFFFFF",
    val refractionScale: Float = 1.0f,
    val chromaticAberrationScale: Float = 1.0f,
    val fresnelStrength: Float = 0.7f,
    val highlightAlpha: Float = 0.88f,
    val allowlistedShaderId: String = "liquid_glass_v2",
)

@Immutable
data class RemoteUiConfig(
    val versionCode: Long = 0L,
    val versionName: String = "built-in-safe",
    val lockScreen: RemoteLockScreenConfig = RemoteLockScreenConfig(),
    val pinScreen: RemotePinScreenConfig = RemotePinScreenConfig(),
    val resources: List<RemoteResourceEntry> = emptyList(),
    val resolvedWallpaperLocalPath: String? = null,
) {
    companion object {
        val BuiltInSafeDefault = RemoteUiConfig()

        private val ALLOWLISTED_SHADERS = setOf(
            "liquid_glass_v1",
            "liquid_glass_v2",
            "liquid_glass_frosted",
        )

        fun parseFromVerifiedManifestJson(
            manifestJson: String,
            localResourceResolver: (String) -> String? = { null },
        ): RemoteUiConfig {
            val root = JSONObject(manifestJson)
            val versionCode = root.optLong("version_code", 0L)
            val versionName = root.optString("version_name", "1.0.0")

            val lockObj = root.optJSONObject("lockscreen") ?: JSONObject()
            val wallpaperId = lockObj.optString("wallpaper_resource_id", "").takeIf { it.isNotBlank() }

            val lockConfig = RemoteLockScreenConfig(
                showClockWidget = lockObj.optBoolean("show_clock_widget", false),
                statusPromptText = lockObj.optString(
                    "status_prompt_text",
                    "Vuốt lên để mở bằng Sinh trắc học hoặc Nhập mật khẩu",
                ).take(120),
                promptColorHex = sanitizeHexColor(lockObj.optString("prompt_color_hex"), "#F0FFFFFF"),
                errorPromptColorHex = sanitizeHexColor(lockObj.optString("error_prompt_color_hex"), "#FFFFB4AB"),
                wallpaperResourceId = wallpaperId,
                unlockFadeDurationMs = lockObj.optInt("unlock_fade_duration_ms", 200).coerceIn(80, 600),
                shakeDurationMs = lockObj.optInt("shake_duration_ms", 42).coerceIn(20, 120),
            )

            val pinObj = root.optJSONObject("pin_screen") ?: JSONObject()
            val shape = when (pinObj.optString("key_shape", "CIRCLE").uppercase()) {
                "ROUNDED_RECT" -> PinKeyShape.ROUNDED_RECT
                "SQUIRCLE" -> PinKeyShape.SQUIRCLE
                else -> PinKeyShape.CIRCLE
            }
            val rawShader = pinObj.optString("allowlisted_shader_id", "liquid_glass_v2")
            val safeShader = if (rawShader in ALLOWLISTED_SHADERS) rawShader else "liquid_glass_v2"

            val pinConfig = RemotePinScreenConfig(
                keySizeDp = pinObj.optDouble("key_size_dp", 82.0).toFloat().coerceIn(64f, 96f),
                keyHorizontalSpacingDp = pinObj.optDouble("key_horizontal_spacing_dp", 24.0).toFloat().coerceIn(10f, 36f),
                keyVerticalSpacingDp = pinObj.optDouble("key_vertical_spacing_dp", 16.0).toFloat().coerceIn(8f, 32f),
                keyShape = shape,
                keyCornerRadiusDp = pinObj.optDouble("key_corner_radius_dp", 24.0).toFloat().coerceIn(8f, 44f),
                digitFontSizeSp = pinObj.optDouble("digit_font_size_sp", 34.0).toFloat().coerceIn(22f, 44f),
                letterFontSizeSp = pinObj.optDouble("letter_font_size_sp", 11.0).toFloat().coerceIn(8f, 16f),
                digitColorHex = sanitizeHexColor(pinObj.optString("digit_color_hex"), "#FFFFFFFF"),
                letterColorHex = sanitizeHexColor(pinObj.optString("letter_color_hex"), "#CCFFFFFF"),
                dotSizeDp = pinObj.optDouble("dot_size_dp", 13.0).toFloat().coerceIn(8f, 20f),
                dotSpacingDp = pinObj.optDouble("dot_spacing_dp", 20.0).toFloat().coerceIn(10f, 32f),
                dotFilledColorHex = sanitizeHexColor(pinObj.optString("dot_filled_color_hex"), "#FFFFFFFF"),
                dotEmptyBorderColorHex = sanitizeHexColor(pinObj.optString("dot_empty_border_color_hex"), "#B3FFFFFF"),
                refractionScale = pinObj.optDouble("refraction_scale", 1.0).toFloat().coerceIn(0.5f, 1.5f),
                chromaticAberrationScale = pinObj.optDouble("chromatic_aberration_scale", 1.0).toFloat().coerceIn(0.0f, 1.5f),
                fresnelStrength = pinObj.optDouble("fresnel_strength", 0.7).toFloat().coerceIn(0.0f, 1.0f),
                highlightAlpha = pinObj.optDouble("highlight_alpha", 0.88).toFloat().coerceIn(0.3f, 1.0f),
                allowlistedShaderId = safeShader,
            )

            val resources = mutableListOf<RemoteResourceEntry>()
            val arr = root.optJSONArray("resources")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    resources.add(
                        RemoteResourceEntry(
                            id = item.optString("id"),
                            type = item.optString("type"),
                            path = item.optString("path"),
                            sha256 = item.optString("sha256"),
                            size = item.optLong("size", 0L),
                        )
                    )
                }
            }

            val resolvedWallpaper = wallpaperId?.let { id ->
                resources.firstOrNull { it.id == id }?.let { localResourceResolver(it.path) }
            }

            return RemoteUiConfig(
                versionCode = versionCode,
                versionName = versionName,
                lockScreen = lockConfig,
                pinScreen = pinConfig,
                resources = resources,
                resolvedWallpaperLocalPath = resolvedWallpaper,
            )
        }

        private fun sanitizeHexColor(raw: String?, fallback: String): String {
            if (raw.isNullOrBlank()) return fallback
            val trimmed = raw.trim()
            val regex = Regex("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")
            return if (regex.matches(trimmed)) trimmed else fallback
        }
    }
}
