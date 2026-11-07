package com.digitallock.ui.lock

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.digitallock.data.DigitalLockSettings
import com.digitallock.graphics.LockBackgroundRenderer
import com.digitallock.graphics.PinGlassConfig
import com.digitallock.graphics.PinGlassRenderer
import com.digitallock.graphics.rememberLifecycleAwareGlassController
import com.digitallock.lock.ActiveRecoverySheet
import com.digitallock.lock.LockUiState
import com.digitallock.lock.PinVerificationPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.material3.Text
import component.liquid.LayerBackdrop
import component.liquid.rememberLayerBackdrop
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private data class PinPadKeySpec(
    val digit: Char,
    val letters: String,
    val lightAngleDegrees: Float,
)

private val PIN_GRID_ROWS: List<List<PinPadKeySpec>> = listOf(
    listOf(
        PinPadKeySpec('1', "", -55f),
        PinPadKeySpec('2', "ABC", -35f),
        PinPadKeySpec('3', "DEF", -15f),
    ),
    listOf(
        PinPadKeySpec('4', "GHI", -45f),
        PinPadKeySpec('5', "JKL", -25f),
        PinPadKeySpec('6', "MNO", -5f),
    ),
    listOf(
        PinPadKeySpec('7', "PQRS", -35f),
        PinPadKeySpec('8', "TUV", -15f),
        PinPadKeySpec('9', "WXYZ", 5f),
    ),
)

/**
 * Pure iOS 27 Inspired Liquid Glass Lock Screen (UI System B).
 *
 * Strictly separated from Material 3 Expressive:
 * - Uses a single shared `LayerBackdrop` captured from `LockBackgroundRenderer.SharedRefractionBackground`.
 * - Each of the circular PIN keys (`1..9`, `0`) is an independent refractive glass surface
 *   via `PinGlassRenderer.LiquidGlassPinButton`.
 * - Zero button zoom/scale or translation on touch.
 * - Localized horizontal shake animation strictly on the PIN dot indicator cluster when a wrong PIN is entered.
 */
@Composable
fun LiquidGlassLockScreen(
    uiStateFlow: StateFlow<LockUiState>,
    settingsFlow: StateFlow<DigitalLockSettings>,
    mediaProjectionBitmapFlow: StateFlow<Bitmap?>,
    onDigitPressed: (Char) -> Unit,
    onBackspacePressed: () -> Unit,
    onBiometricRequested: () -> Unit,
    onOpenRecoverySheet: (ActiveRecoverySheet) -> Unit,
    onDismissRecoverySheet: () -> Unit,
    onSubmitRecoveryCode: (String) -> Unit,
    onSubmitSecurityAnswer: (String) -> Unit,
    onRequestMediaProjectionBackdrop: () -> Unit,
) {
    val uiState by uiStateFlow.collectAsState()
    val settings by settingsFlow.collectAsState()
    val mediaProjectionBitmap by mediaProjectionBitmapFlow.collectAsState()

    val density = LocalDensity.current
    val glassController = rememberLifecycleAwareGlassController(density.density)
    val isGraphicsActive by glassController.isRenderingActive

    // Single shared backdrop used by all PIN buttons and capsules
    val sharedBackdrop: LayerBackdrop = rememberLayerBackdrop()

    // Subtle slow ambient wave breathing (paused when screen is not active)
    val infiniteTransition = rememberInfiniteTransition(label = "LiquidAmbient")
    val ambientPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 11_000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "AmbientWavePhase",
    )

    // Clock & Date state updated on minute ticks
    var currentTimeText by remember { mutableStateOf("") }
    var currentDateText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
        while (true) {
            val now = Date()
            currentTimeText = timeFormat.format(now)
            currentDateText = dateFormat.format(now)
            delay(15_000L)
        }
    }

    // Horizontal shake animation localized strictly to the PIN indicator cluster
    val dotsShakeOffsetPx = remember { Animatable(0f) }
    LaunchedEffect(uiState.phase) {
        if (uiState.phase == PinVerificationPhase.ERROR_SHAKE) {
            val keyframes = floatArrayOf(-22f, 22f, -16f, 16f, -9f, 9f, 0f)
            for (target in keyframes) {
                dotsShakeOffsetPx.animateTo(
                    targetValue = target,
                    animationSpec = tween(durationMillis = 52),
                )
            }
        } else {
            dotsShakeOffsetPx.snapTo(0f)
        }
    }

    // Smooth unlock fade-out transition
    val screenUnlockAlpha = remember { Animatable(1f) }
    LaunchedEffect(uiState.phase) {
        if (uiState.phase == PinVerificationPhase.UNLOCKING_SUCCESS) {
            screenUnlockAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
            )
        } else {
            screenUnlockAlpha.snapTo(1f)
        }
    }

    val tunedPinConfig = remember(settings.refractionIntensityScale, settings.chromaticAberrationScale) {
        val rScale = settings.refractionIntensityScale.coerceIn(0.5f, 1.5f)
        val cScale = settings.chromaticAberrationScale.coerceIn(0.0f, 1.5f)
        PinGlassConfig(
            buttonSize = 80.dp,
            restingRefractionHeight = (18f * rScale).dp,
            pressedRefractionHeight = (22f * rScale).dp,
            restingRefractionAmount = (20f * rScale).dp,
            pressedRefractionAmount = (26f * rScale).dp,
            depthEffect = true,
            restingChromaticAberration = 0.14f * cScale,
            pressedChromaticAberration = 0.20f * cScale,
        )
    }

    val canAcceptInput = uiState.phase == PinVerificationPhase.IDLE_ENTERING &&
        uiState.lockoutRemainingSeconds <= 0L

    LockBackgroundRenderer.SharedRefractionBackground(
        sharedBackdrop = sharedBackdrop,
        customWallpaperUri = settings.customWallpaperUri,
        mediaProjectionBitmap = mediaProjectionBitmap,
        ambientPhase = if (isGraphicsActive) ambientPhase else 0.5f,
        modifier = Modifier
            .fillMaxSize()
            .alpha(screenUnlockAlpha.value)
            .pointerInput(settings.biometricEnabled) {
                var accumulatedUpwardDrag = 0f
                detectVerticalDragGestures(
                    onDragStart = { accumulatedUpwardDrag = 0f },
                    onVerticalDrag = { _, dragAmount ->
                        if (dragAmount < 0f) {
                            accumulatedUpwardDrag += -dragAmount
                            if (accumulatedUpwardDrag > 120f) {
                                accumulatedUpwardDrag = 0f
                                onBiometricRequested()
                            }
                        }
                    },
                )
            },
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 28.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // TOP SECTION: Dynamic Island Security Pill + Clock & Date
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Left quick action: MediaProjection live texture consent button
                    PinGlassRenderer.LiquidGlassPinButton(
                        backdrop = sharedBackdrop,
                        enabled = true,
                        isGraphicsActive = isGraphicsActive,
                        config = tunedPinConfig.copy(buttonSize = 42.dp),
                        contentDescriptionText = "Capture live wallpaper backdrop",
                        onClick = onRequestMediaProjectionBackdrop,
                    ) {
                        Text(
                            text = "◉",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 15.sp,
                        )
                    }

                    // Center iOS-style Security Capsule (Lock + Face/Biometric icon)
                    PinGlassRenderer.LiquidGlassCapsule(
                        backdrop = sharedBackdrop,
                        isGraphicsActive = isGraphicsActive,
                        cornerRadius = 28.dp,
                        modifier = Modifier
                            .width(196.dp)
                            .height(50.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onBiometricRequested,
                            ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LockPadlockGlyph(
                                unlocked = uiState.phase == PinVerificationPhase.UNLOCKING_SUCCESS,
                            )
                            BiometricScannerGlyph(
                                active = settings.biometricEnabled,
                            )
                        }
                    }

                    // Right quick action: Recovery options menu
                    PinGlassRenderer.LiquidGlassPinButton(
                        backdrop = sharedBackdrop,
                        enabled = true,
                        isGraphicsActive = isGraphicsActive,
                        config = tunedPinConfig.copy(buttonSize = 42.dp),
                        contentDescriptionText = "Open recovery methods",
                        onClick = { onOpenRecoverySheet(ActiveRecoverySheet.RECOVERY_CODE) },
                    ) {
                        Text(
                            text = "⋮",
                            color = Color.White.copy(alpha = 0.90f),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = currentTimeText,
                    color = Color.White.copy(alpha = 0.96f),
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Light,
                    fontFamily = FontFamily.SansSerif,
                )
                Text(
                    text = currentDateText,
                    color = Color.White.copy(alpha = 0.76f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal,
                )
            }

            // MIDDLE SECTION: Prompt + PIN Indicator Dots Cluster
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = uiState.statusPrompt,
                    color = if (uiState.phase == PinVerificationPhase.ERROR_SHAKE) {
                        Color(0xFFFFB4AB)
                    } else {
                        Color.White.copy(alpha = 0.92f)
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                Spacer(modifier = Modifier.height(22.dp))

                Row(
                    modifier = Modifier.offset {
                        IntOffset(dotsShakeOffsetPx.value.roundToInt(), 0)
                    },
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val totalDots = uiState.targetPinLength.coerceIn(4, 12)
                    val filledCount = uiState.enteredPin.length
                    for (index in 0 until totalDots) {
                        val isFilled = index < filledCount
                        val isUnlocking = uiState.phase == PinVerificationPhase.UNLOCKING_SUCCESS
                        val isError = uiState.phase == PinVerificationPhase.ERROR_SHAKE
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(
                                    color = when {
                                        isUnlocking -> Color(0xFF7CF7C8)
                                        isError && isFilled -> Color(0xFFFF897D)
                                        isFilled -> Color.White
                                        else -> Color.Transparent
                                    },
                                    shape = CircleShape,
                                )
                                .border(
                                    width = 1.6.dp,
                                    color = when {
                                        isUnlocking -> Color(0xFF7CF7C8)
                                        isError -> Color(0xFFFF897D)
                                        else -> Color.White.copy(alpha = 0.86f)
                                    },
                                    shape = CircleShape,
                                ),
                        )
                    }
                }
            }

            // BOTTOM SECTION: 3x4 Circular Liquid Glass PIN Keypad + Bottom Bar
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            ) {
                // Rows 1..3 (Keys 1..9)
                PIN_GRID_ROWS.forEach { rowSpecs ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(26.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        rowSpecs.forEach { spec ->
                            PinGlassRenderer.LiquidGlassPinButton(
                                backdrop = sharedBackdrop,
                                enabled = canAcceptInput,
                                isGraphicsActive = isGraphicsActive,
                                config = tunedPinConfig,
                                keyAngleDegrees = spec.lightAngleDegrees,
                                contentDescriptionText = "PIN digit ${spec.digit}",
                                onClick = { onDigitPressed(spec.digit) },
                            ) {
                                Text(
                                    text = spec.digit.toString(),
                                    color = Color.White,
                                    fontSize = 34.sp,
                                    fontWeight = FontWeight.Normal,
                                )
                                if (spec.letters.isNotEmpty()) {
                                    Text(
                                        text = spec.letters,
                                        color = Color.White.copy(alpha = 0.82f),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 2.2.sp,
                                    )
                                }
                            }
                        }
                    }
                }

                // Row 4: Centered '0' key
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    PinGlassRenderer.LiquidGlassPinButton(
                        backdrop = sharedBackdrop,
                        enabled = canAcceptInput,
                        isGraphicsActive = isGraphicsActive,
                        config = tunedPinConfig,
                        keyAngleDegrees = -25f,
                        contentDescriptionText = "PIN digit 0",
                        onClick = { onDigitPressed('0') },
                    ) {
                        Text(
                            text = "0",
                            color = Color.White,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Normal,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Bottom row: Emergency / Recovery on left, Cancel / Delete on right
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Khôi phục",
                        color = Color.White.copy(alpha = 0.90f),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                onOpenRecoverySheet(ActiveRecoverySheet.RECOVERY_CODE)
                            }
                            .padding(vertical = 8.dp, horizontal = 12.dp),
                    )

                    Text(
                        text = if (uiState.enteredPin.isEmpty()) "Hủy" else "Xóa",
                        color = Color.White.copy(alpha = 0.90f),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                if (uiState.enteredPin.isNotEmpty()) {
                                    onBackspacePressed()
                                }
                            }
                            .padding(vertical = 8.dp, horizontal = 12.dp),
                    )
                }
            }
        }

        // Liquid Glass Recovery Sheet Overlay (Recovery Code or Security Question)
        if (uiState.activeRecoverySheet != ActiveRecoverySheet.NONE) {
            LiquidGlassRecoverySheet(
                sharedBackdrop = sharedBackdrop,
                isGraphicsActive = isGraphicsActive,
                activeSheet = uiState.activeRecoverySheet,
                securityQuestionPrompt = uiState.securityQuestionPrompt,
                onSwitchSheet = onOpenRecoverySheet,
                onDismiss = onDismissRecoverySheet,
                onSubmitRecoveryCode = onSubmitRecoveryCode,
                onSubmitSecurityAnswer = onSubmitSecurityAnswer,
            )
        }
    }
}

@Composable
private fun PinGlassRenderer.LiquidGlassPinButton(
    backdrop: LayerBackdrop?,
    enabled: Boolean,
    isGraphicsActive: Boolean,
    config: PinGlassConfig,
    keyAngleDegrees: Float,
    contentDescriptionText: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    LiquidGlassPinButton(
        backdrop = backdrop,
        overlayBackdrop = null,
        enabled = enabled,
        isGraphicsActive = isGraphicsActive,
        config = config,
        keyAngleOffsetDegrees = keyAngleDegrees,
        contentDescriptionText = contentDescriptionText,
        onClick = onClick,
        content = content,
    )
}

@Composable
private fun LockPadlockGlyph(unlocked: Boolean) {
    val glyphColor = if (unlocked) Color(0xFF7CF7C8) else Color.White.copy(alpha = 0.90f)
    Canvas(modifier = Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        // Shackle arc
        drawArc(
            color = glyphColor,
            startAngle = 180f,
            sweepAngle = if (unlocked) 130f else 180f,
            useCenter = false,
            topLeft = Offset(w * 0.25f, h * 0.08f),
            size = androidx.compose.ui.geometry.Size(w * 0.50f, h * 0.45f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
        // Padlock body
        drawRoundRect(
            color = glyphColor,
            topLeft = Offset(w * 0.18f, h * 0.42f),
            size = androidx.compose.ui.geometry.Size(w * 0.64f, h * 0.50f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx()),
        )
    }
}

@Composable
private fun BiometricScannerGlyph(active: Boolean) {
    val strokeColor = if (active) Color(0xFF72D572) else Color.White.copy(alpha = 0.45f)
    Canvas(modifier = Modifier.size(24.dp)) {
        val s = size.minDimension
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        // Corner brackets + face/fingerprint arcs
        drawArc(
            color = strokeColor,
            startAngle = 180f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(s * 0.1f, s * 0.1f),
            size = androidx.compose.ui.geometry.Size(s * 0.3f, s * 0.3f),
            style = stroke,
        )
        drawArc(
            color = strokeColor,
            startAngle = 270f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(s * 0.6f, s * 0.1f),
            size = androidx.compose.ui.geometry.Size(s * 0.3f, s * 0.3f),
            style = stroke,
        )
        drawArc(
            color = strokeColor,
            startAngle = 90f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(s * 0.1f, s * 0.6f),
            size = androidx.compose.ui.geometry.Size(s * 0.3f, s * 0.3f),
            style = stroke,
        )
        drawArc(
            color = strokeColor,
            startAngle = 0f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(s * 0.6f, s * 0.6f),
            size = androidx.compose.ui.geometry.Size(s * 0.3f, s * 0.3f),
            style = stroke,
        )
        // Smile curve inside scanner
        drawArc(
            color = strokeColor,
            startAngle = 25f,
            sweepAngle = 130f,
            useCenter = false,
            topLeft = Offset(s * 0.32f, s * 0.36f),
            size = androidx.compose.ui.geometry.Size(s * 0.36f, s * 0.32f),
            style = stroke,
        )
    }
}

@Composable
private fun LiquidGlassRecoverySheet(
    sharedBackdrop: LayerBackdrop,
    isGraphicsActive: Boolean,
    activeSheet: ActiveRecoverySheet,
    securityQuestionPrompt: String?,
    onSwitchSheet: (ActiveRecoverySheet) -> Unit,
    onDismiss: () -> Unit,
    onSubmitRecoveryCode: (String) -> Unit,
    onSubmitSecurityAnswer: (String) -> Unit,
) {
    var inputText by remember(activeSheet) { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.48f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        PinGlassRenderer.LiquidGlassCapsule(
            backdrop = sharedBackdrop,
            isGraphicsActive = isGraphicsActive,
            cornerRadius = 32.dp,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = if (activeSheet == ActiveRecoverySheet.RECOVERY_CODE) {
                        "Khôi phục bằng Mã dự phòng"
                    } else {
                        "Khôi phục bằng Câu hỏi bảo mật"
                    },
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                Text(
                    text = if (activeSheet == ActiveRecoverySheet.RECOVERY_CODE) {
                        "Nhập Mã khôi phục 16 ký tự (XXXX-XXXX-XXXX-XXXX) hoặc đóng hộp thoại này để nhập trực tiếp Mã PIN phụ trên bàn phím kính."
                    } else {
                        securityQuestionPrompt ?: "Chưa thiết lập câu hỏi bảo mật."
                    },
                    color = Color.White.copy(alpha = 0.80f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )

                BasicTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                    ),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .border(1.dp, Color.White.copy(alpha = 0.30f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (!securityQuestionPrompt.isNullOrBlank()) {
                        Text(
                            text = if (activeSheet == ActiveRecoverySheet.RECOVERY_CODE) {
                                "Dùng Câu hỏi bảo mật"
                            } else {
                                "Dùng Mã khôi phục"
                            },
                            color = Color(0xFF98FFE8),
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clickable {
                                    onSwitchSheet(
                                        if (activeSheet == ActiveRecoverySheet.RECOVERY_CODE) {
                                            ActiveRecoverySheet.SECURITY_QUESTION
                                        } else {
                                            ActiveRecoverySheet.RECOVERY_CODE
                                        }
                                    )
                                }
                                .padding(8.dp),
                        )
                    } else {
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            text = "Đóng",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 15.sp,
                            modifier = Modifier
                                .clickable(onClick = onDismiss)
                                .padding(8.dp),
                        )
                        Text(
                            text = "Xác nhận",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable {
                                    if (activeSheet == ActiveRecoverySheet.RECOVERY_CODE) {
                                        onSubmitRecoveryCode(inputText)
                                    } else {
                                        onSubmitSecurityAnswer(inputText)
                                    }
                                }
                                .padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}
