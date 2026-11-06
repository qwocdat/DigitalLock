// Copyright 2026, compose-miuix-ui contributors & DigitalLock Engineering Team
// SPDX-License-Identifier: Apache-2.0
//
// Core refraction, specular BloomStroke highlight, gravity-quantized light rotation,
// and InnerShadow adapted from component.liquid (Kyant0/AndroidLiquidGlass, Apache 2.0).
// TabBar-specific drag/zoom behaviors (DampedDragAnimation, LocalIosTabScale, panelOffset,
// velocity-based scaleX/scaleY distortion, pressProgress zoom) have been completely removed.

package com.digitallock.graphics

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics. onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import component.liquid.InnerShadow
import component.liquid.innerShadow
import component.liquid.lens
import component.liquid.rememberCombinedBackdrop
import component.liquid.vibrancy
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import top.yukonga.miuix.kmp.blur.sensor.rememberDeviceTilt

// Mirrors HighlightStyle.kt's LIGHT_REF for dualPeak specular rim lighting.
private const val LIGHT_REF_X = 0.5f
private const val LIGHT_REF_Y = 0.7f
private const val GRAVITY_DIR_THRESHOLD_SQ = 0.01f
private const val GRAVITY_ANGLE_STEP_RAD = (3.0 * PI / 180.0).toFloat()

/**
 * Optical calibration profile specifically tuned for circular 78dp–84dp PIN keys.
 *
 * Notice how touch interaction (`pressGlowProgress`) modulates ONLY internal optical
 * refraction depth, specular rim intensity, light angle sweep, and inner shadow depth—
 * NEVER button scale (`scaleX == 1f`, `scaleY == 1f`) or position (`translation == 0f`).
 */
@Immutable
data class PinGlassConfig(
    val buttonSize: Dp = 80.dp,
    val restingRefractionHeight: Dp = 18.dp,
    val pressedRefractionHeight: Dp = 22.dp,
    val restingRefractionAmount: Dp = 20.dp,
    val pressedRefractionAmount: Dp = 26.dp,
    val depthEffect: Boolean = true,
    val restingChromaticAberration: Float = 0.14f,
    val pressedChromaticAberration: Float = 0.20f,
    val microDiffusionBlurRadius: Dp = 2.5.dp,
    val restingHighlightAlpha: Float = 0.72f,
    val pressedHighlightAlpha: Float = 0.98f,
    val restingInnerShadowRadius: Dp = 6.dp,
    val pressedInnerShadowRadius: Dp = 10.dp,
    val restingSurfaceTintAlpha: Float = 0.06f,
    val pressedSurfaceTintAlpha: Float = 0.16f,
) {
    companion object {
        @Stable
        val DefaultPinPad = PinGlassConfig()
    }
}

private val pinRimSpecularBase: Highlight = Highlight(
    width = 1.25.dp,
    alpha = 1f,
    style = BloomStroke(
        color = Color.White.copy(alpha = 0.18f),
        innerBlurRadius = 2.5.dp,
        primaryLight = LightSource(
            position = LightPosition(0.35f, -0.35f, -0.06f),
            color = Color.White,
            intensity = 1.0f,
        ),
        secondaryLight = LightSource(
            position = LightPosition(0.65f, 0.85f, -0.45f),
            color = Color(0xFFB8E8FF),
            intensity = 0.48f,
        ),
        dualPeak = true,
    ),
)

@Composable
internal fun rememberQuantizedPinGravityAngle(): State<Float> {
    val tiltState = rememberDeviceTilt()
    return remember(tiltState) {
        derivedStateOf {
            val tilt = tiltState.value
            val gx = tilt.gravityX
            val gy = tilt.gravityY
            val gMagSq = gx * gx + gy * gy
            if (gMagSq > GRAVITY_DIR_THRESHOLD_SQ) {
                (atan2(gy, gx) / GRAVITY_ANGLE_STEP_RAD).roundToInt() * GRAVITY_ANGLE_STEP_RAD
            } else {
                (-PI / 2).toFloat()
            }
        }
    }
}

@Composable
internal fun rememberPinGravityRotatedHighlight(
    base: Highlight = pinRimSpecularBase,
    extraDegrees: Float = -35f,
): State<Highlight> {
    val gravityAngle = rememberQuantizedPinGravityAngle()
    return remember(gravityAngle, base, extraDegrees) {
        derivedStateOf {
            val baseStyle = base.style as BloomStroke
            val basePrimary = baseStyle.primaryLight
            val rad = gravityAngle.value + (extraDegrees * PI / 180.0).toFloat()
            base.copy(
                style = baseStyle.copy(
                    primaryLight = basePrimary.copy(
                        position = LightPosition(
                            x = LIGHT_REF_X + cos(rad),
                            y = LIGHT_REF_Y + sin(rad),
                            z = basePrimary.position.z,
                        ),
                    ),
                ),
            )
        }
    }
}

/**
 * Core renderer for DigitalLock's iOS 27 Inspired Liquid Glass PIN surfaces.
 *
 * Each PIN button samples from the single shared [backdrop] (or [CombinedBackdrop] if a
 * secondary tint backdrop is layered) and applies the real `lens()` runtime shader with
 * radial depth meniscus and controlled 7-band chromatic dispersion.
 *
 * Strictly enforces:
 * - Zero scale/zoom on touch (`scaleX = 1f`, `scaleY = 1f` Always).
 * - Zero layout translation or jitter on touch.
 * - Independent optical lens surface per key.
 */
object PinGlassRenderer {

    @Composable
    fun LiquidGlassPinButton(
        backdrop: Backdrop?,
        overlayBackdrop: Backdrop? = null,
        enabled: Boolean = true,
        isGraphicsActive: Boolean = true,
        config: PinGlassConfig = PinGlassConfig.DefaultPinPad,
        keyAngleOffsetDegrees: Float = -35f,
        contentDescriptionText: String,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        content: @Composable () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        val currentOnClick by rememberUpdatedState(onClick)
        val currentEnabled by rememberUpdatedState(enabled)

        // Optical glow/refraction modulation state (0f = resting, 1f = touched).
        // Never used to scale or translate the button.
        val pressOpticalProgress = remember { Animatable(0f) }

        val effectiveBackdrop = remember(backdrop, overlayBackdrop) {
            when {
                backdrop != null && overlayBackdrop != null -> CombinedBackdrop(backdrop, overlayBackdrop)
                else -> backdrop
            }
        }

        val rotatedHighlight = rememberPinGravityRotatedHighlight(
            base = pinRimSpecularBase,
            extraDegrees = keyAngleOffsetDegrees,
        )

        val buttonShape: Shape = CircleShape

        Box(
            modifier = modifier
                .size(config.buttonSize)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = contentDescriptionText
                    if (currentEnabled) {
                        onClick {
                            currentOnClick()
                            true
                        }
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!currentEnabled) return@awaitEachGesture
                        down.consume()
                        scope.launch {
                            pressOpticalProgress.animateTo(
                                targetValue = 1f,
                                animationSpec = spring(dampingRatio = 0.85f, stiffness = 900f),
                            )
                        }
                        val up = waitForUpOrCancellation()
                        if (up != null && currentEnabled) {
                            up.consume()
                            currentOnClick()
                        }
                        scope.launch {
                            pressOpticalProgress.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
                            )
                        }
                    }
                }
                .dropShadow(
                    shape = buttonShape,
                    shadow = Shadow(
                        radius = 12.dp,
                        color = Color.Black,
                        alpha = 0.24f,
                    ),
                )
                .then(
                    if (isGraphicsActive && effectiveBackdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = effectiveBackdrop,
                            shape = { buttonShape },
                            effects = {
                                val p = pressOpticalProgress.value
                                val refHeightPx = lerp(
                                    config.restingRefractionHeight.toPx(),
                                    config.pressedRefractionHeight.toPx(),
                                    p,
                                )
                                val refAmountPx = lerp(
                                    config.restingRefractionAmount.toPx(),
                                    config.pressedRefractionAmount.toPx(),
                                    p,
                                )
                                val aberration = lerp(
                                    config.restingChromaticAberration,
                                    config.pressedChromaticAberration,
                                    p,
                                )

                                // Ensure sufficient sampling padding before micro-diffusion & lens refraction
                                padding = maxOf(padding, refAmountPx + 8.dp.toPx())
                                vibrancy()
                                if (config.microDiffusionBlurRadius > 0.dp) {
                                    val blurPx = config.microDiffusionBlurRadius.toPx()
                                    blur(blurPx, blurPx)
                                }
                                lens(
                                    refractionHeight = refHeightPx,
                                    refractionAmount = refAmountPx,
                                    depthEffect = config.depthEffect,
                                    chromaticAberration = aberration,
                                )
                            },
                            highlight = {
                                val p = pressOpticalProgress.value
                                val alpha = lerp(
                                    config.restingHighlightAlpha,
                                    config.pressedHighlightAlpha,
                                    p,
                                )
                                val base = rotatedHighlight.value
                                val style = base.style as BloomStroke
                                // Subtle light sweep across the glass surface on touch without moving the button
                                val sweepOffset = p * 0.22f
                                base.copy(
                                    width = lerp(1.25.dp.value, 1.75.dp.value, p).dp,
                                    alpha = alpha,
                                    style = style.copy(
                                        primaryLight = style.primaryLight.copy(
                                            position = LightPosition(
                                                x = style.primaryLight.position.x + sweepOffset,
                                                y = style.primaryLight.position.y - sweepOffset * 0.5f,
                                                z = style.primaryLight.position.z,
                                            ),
                                            intensity = lerp(1.0f, 1.35f, p),
                                        ),
                                    ),
                                )
                            },
                            // CRITICAL: No layerBlock scaling or translation allowed on PIN keys.
                            layerBlock = null,
                            onDrawSurface = {
                                val p = pressOpticalProgress.value
                                val baseAlpha = lerp(
                                    config.restingSurfaceTintAlpha,
                                    config.pressedSurfaceTintAlpha,
                                    p,
                                )
                                // Subtle Fresnel radial rim sheen + touch light sweep across the stationary lens
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = baseAlpha * 0.45f),
                                            Color.White.copy(alpha = baseAlpha * 0.85f),
                                            Color(0xFFD8F3FF).copy(alpha = baseAlpha * 1.45f),
                                        ),
                                        center = Offset(
                                            x = size.width * (0.38f + 0.24f * p),
                                            y = size.height * (0.32f - 0.10f * p),
                                        ),
                                        radius = size.minDimension * 0.62f,
                                    ),
                                )
                            },
                        )
                    } else {
                        Modifier
                            .clip(buttonShape)
                            .background(Color.White.copy(alpha = 0.12f), buttonShape)
                    },
                )
                .innerShadow(shape = buttonShape) {
                    val p = pressOpticalProgress.value
                    val shadowRadius = lerp(
                        config.restingInnerShadowRadius.value,
                        config.pressedInnerShadowRadius.value,
                        p,
                    ).dp
                    InnerShadow(
                        radius = shadowRadius,
                        offset = DpOffset(0.dp, (2f + 2f * p).dp),
                        color = Color(0xFF030B17).copy(alpha = lerp(0.24f, 0.38f, p)),
                        alpha = 1f,
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                content()
            }
        }
    }

    /**
     * Renders a non-interactive or pill-shaped Liquid Glass surface (e.g., Top Security Pill
     * or Recovery Action Chip) sharing the same [backdrop].
     */
    @Composable
    fun LiquidGlassCapsule(
        backdrop: LayerBackdrop?,
        isGraphicsActive: Boolean = true,
        cornerRadius: Dp = 28.dp,
        modifier: Modifier = Modifier,
        content: @Composable () -> Unit,
    ) {
        val capsuleShape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
        val highlightState = rememberPinGravityRotatedHighlight(extraDegrees = -20f)

        Box(
            modifier = modifier
                .then(
                    if (isGraphicsActive && backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { capsuleShape },
                            effects = {
                                padding = maxOf(padding, 20.dp.toPx())
                                vibrancy()
                                blur(3.dp.toPx(), 3.dp.toPx())
                                lens(
                                    refractionHeight = 14.dp.toPx(),
                                    refractionAmount = 16.dp.toPx(),
                                    depthEffect = true,
                                    chromaticAberration = 0.12f,
                                )
                            },
                            highlight = { highlightState.value.copy(alpha = 0.80f) },
                            layerBlock = null,
                            onDrawSurface = {
                                drawRect(Color(0xFF050A14).copy(alpha = 0.42f))
                            },
                        )
                    } else {
                        Modifier
                            .clip(capsuleShape)
                            .background(Color(0xFF050A14).copy(alpha = 0.65f), capsuleShape)
                    },
                )
                .innerShadow(shape = capsuleShape) {
                    InnerShadow(
                        radius = 6.dp,
                        offset = DpOffset(0.dp, 2.dp),
                        color = Color.Black.copy(alpha = 0.28f),
                        alpha = 1f,
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}
