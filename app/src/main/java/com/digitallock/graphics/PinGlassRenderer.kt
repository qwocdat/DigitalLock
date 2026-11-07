// Copyright 2026, compose-miuix-ui contributors & DigitalLock Engineering Team
// SPDX-License-Identifier: Apache-2.0

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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import component.liquid.Backdrop
import component.liquid.BloomStroke
import component.liquid.CombinedBackdrop
import component.liquid.Highlight
import component.liquid.InnerShadow
import component.liquid.LayerBackdrop
import component.liquid.LightPosition
import component.liquid.LightSource
import component.liquid.blur
import component.liquid.drawBackdrop
import component.liquid.innerShadow
import component.liquid.lens
import component.liquid.rememberDeviceTilt
import component.liquid.vibrancy
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val LIGHT_REF_X = 0.5f
private const val LIGHT_REF_Y = 0.7f
private const val GRAVITY_DIR_THRESHOLD_SQ = 0.01f
private const val GRAVITY_ANGLE_STEP_RAD = (3.0 * PI / 180.0).toFloat()

@Immutable
data class PinGlassConfig(
    val buttonSize: Dp = 82.dp,
    val restingRefractionHeight: Dp = 24.dp,
    val pressedRefractionHeight: Dp = 30.dp,
    val restingRefractionAmount: Dp = 28.dp,
    val pressedRefractionAmount: Dp = 36.dp,
    val depthEffect: Boolean = true,
    val restingChromaticAberration: Float = 0.18f,
    val pressedChromaticAberration: Float = 0.28f,
    val microDiffusionBlurRadius: Dp = 0.8.dp,
    val restingHighlightAlpha: Float = 0.85f,
    val pressedHighlightAlpha: Float = 1.0f,
    val restingInnerShadowRadius: Dp = 10.dp,
    val pressedInnerShadowRadius: Dp = 16.dp,
) {
    companion object {
        @Stable
        val DefaultPinPad = PinGlassConfig()
    }
}

private val pinRimSpecularBase: Highlight = Highlight(
    width = 1.4.dp,
    alpha = 1f,
    style = BloomStroke(
        color = Color.White.copy(alpha = 0.14f),
        innerBlurRadius = 2.5.dp,
        primaryLight = LightSource(
            position = LightPosition(0.28f, 0.12f, -0.06f),
            color = Color.White,
            intensity = 1.15f,
        ),
        secondaryLight = LightSource(
            position = LightPosition(0.75f, 0.88f, -0.45f),
            color = Color(0xFF8CEBFF),
            intensity = 0.65f,
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
                            x = LIGHT_REF_X + cos(rad) * 0.45f,
                            y = LIGHT_REF_Y + sin(rad) * 0.45f,
                            z = basePrimary.position.z,
                        ),
                    ),
                ),
            )
        }
    }
}

object PinGlassRenderer {

    @Composable
    fun LiquidGlassPinButton(
        backdrop: Backdrop?,
        overlayBackdrop: Backdrop? = null,
        enabled: Boolean = true,
        isGraphicsActive: Boolean = true,
        config: PinGlassConfig = PinGlassConfig.DefaultPinPad,
        keyAngleOffsetDegrees: Float = -35f,
        externalGlowIntensity: Float = 0f,
        localPointerGlowOffset: Offset? = null,
        onButtonPositioned: ((LayoutCoordinates) -> Unit)? = null,
        contentDescriptionText: String,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        content: @Composable () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        val currentOnClick by rememberUpdatedState(onClick)
        val currentEnabled by rememberUpdatedState(enabled)

        val pressOpticalProgress = remember { Animatable(0f) }

        LaunchedEffect(externalGlowIntensity) {
            if (externalGlowIntensity > 0.01f) {
                pressOpticalProgress.snapTo(maxOf(pressOpticalProgress.value, externalGlowIntensity))
            } else if (pressOpticalProgress.value > 0f && externalGlowIntensity == 0f) {
                pressOpticalProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
                )
            }
        }

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
                .then(
                    if (onButtonPositioned != null) {
                        Modifier.onGloballyPositioned(onButtonPositioned)
                    } else {
                        Modifier
                    }
                )
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
                        // Register digit immediately on finger-down for 0ms iOS-like responsiveness!
                        currentOnClick()
                        scope.launch {
                            pressOpticalProgress.snapTo(1f)
                        }
                        waitForUpOrCancellation()
                        scope.launch {
                            pressOpticalProgress.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(dampingRatio = 0.80f, stiffness = 450f),
                            )
                        }
                    }
                }
                .then(
                    if (isGraphicsActive && effectiveBackdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = effectiveBackdrop,
                            shape = { buttonShape },
                            effects = {
                                val p = maxOf(pressOpticalProgress.value, externalGlowIntensity).coerceIn(0f, 1f)
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

                                padding = maxOf(padding, refAmountPx)
                                vibrancy()
                                if (config.microDiffusionBlurRadius.value > 0f) {
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
                                val p = maxOf(pressOpticalProgress.value, externalGlowIntensity).coerceIn(0f, 1f)
                                val alpha = lerp(
                                    config.restingHighlightAlpha,
                                    config.pressedHighlightAlpha,
                                    p,
                                )
                                val base = rotatedHighlight.value
                                val style = base.style as BloomStroke
                                val pointer = localPointerGlowOffset
                                val lightPos = if (pointer != null && size.width > 0f && size.height > 0f) {
                                    LightPosition(
                                        x = (pointer.x / size.width).coerceIn(0f, 1f),
                                        y = (pointer.y / size.height).coerceIn(0f, 1f),
                                        z = style.primaryLight.position.z,
                                    )
                                } else {
                                    LightPosition(
                                        x = style.primaryLight.position.x + p * 0.18f,
                                        y = style.primaryLight.position.y - p * 0.12f,
                                        z = style.primaryLight.position.z,
                                    )
                                }
                                base.copy(
                                    width = lerp(1.3f, 2.2f, p).dp,
                                    alpha = alpha,
                                    style = style.copy(
                                        primaryLight = style.primaryLight.copy(
                                            position = lightPos,
                                            intensity = lerp(1.15f, 1.85f, p),
                                        ),
                                    ),
                                )
                            },
                            layerBlock = null,
                            onDrawSurface = {
                                val p = maxOf(pressOpticalProgress.value, externalGlowIntensity).coerceIn(0f, 1f)
                                // Keep the glass crystal-clear at rest; when touched or dragged over,
                                // emit an iOS 27 interactive luminous glow following the touch/drag coordinate!
                                if (p > 0.01f) {
                                    val glowCenter = localPointerGlowOffset ?: Offset(size.width * 0.5f, size.height * 0.45f)
                                    drawCircle(
                                        brush = Brush.radialGradient(
                                            colors = listOf(
                                                Color.White.copy(alpha = 0.34f * p),
                                                Color(0xFFA8ECFF).copy(alpha = 0.18f * p),
                                                Color.Transparent,
                                            ),
                                            center = glowCenter,
                                            radius = size.minDimension * 0.72f,
                                        ),
                                    )
                                }
                            },
                        )
                    } else {
                        Modifier
                            .clip(buttonShape)
                            .background(Color.White.copy(alpha = 0.08f), buttonShape)
                    },
                )
                .innerShadow(shape = buttonShape) {
                    val p = maxOf(pressOpticalProgress.value, externalGlowIntensity).coerceIn(0f, 1f)
                    val shadowRadius = lerp(
                        config.restingInnerShadowRadius.value,
                        config.pressedInnerShadowRadius.value,
                        p,
                    ).dp
                    InnerShadow(
                        radius = shadowRadius,
                        offset = DpOffset(0.dp, (-3f).dp),
                        color = Color.White.copy(alpha = lerp(0.18f, 0.42f, p)),
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
                                blur(2.dp.toPx(), 2.dp.toPx())
                                lens(
                                    refractionHeight = 16.dp.toPx(),
                                    refractionAmount = 20.dp.toPx(),
                                    depthEffect = true,
                                    chromaticAberration = 0.15f,
                                )
                            },
                            highlight = { highlightState.value.copy(alpha = 0.85f) },
                            layerBlock = null,
                            onDrawSurface = {
                                drawRect(Color(0xFF050A14).copy(alpha = 0.35f))
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
                        color = Color.Black.copy(alpha = 0.25f),
                        alpha = 1f,
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}
