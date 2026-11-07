package com.digitallock.ui.lock

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
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
import component.liquid.LayerBackdrop
import component.liquid.rememberLayerBackdrop
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.hypot
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

    val sharedBackdrop: LayerBackdrop = rememberLayerBackdrop()

    // Tracks each button's window bounds and the user's active touch/drag finger position in window coordinates
    // so dragging across the keypad illuminates nearby buttons with an iOS 27 Liquid Glass glow!
    val buttonBoundsMap = remember { mutableStateMapOf<Char, Rect>() }
    var activePointerInWindow by remember { mutableStateOf<Offset?>(null) }

    val dotsShakeOffsetPx = remember { Animatable(0f) }
    LaunchedEffect(uiState.phase) {
        if (uiState.phase == PinVerificationPhase.ERROR_SHAKE) {
            val keyframes = floatArrayOf(-20f, 20f, -14f, 14f, -8f, 8f, 0f)
            for (target in keyframes) {
                dotsShakeOffsetPx.animateTo(
                    targetValue = target,
                    animationSpec = tween(durationMillis = 42),
                )
            }
        } else {
            dotsShakeOffsetPx.snapTo(0f)
        }
    }

    val screenUnlockAlpha = remember { Animatable(1f) }
    LaunchedEffect(uiState.phase) {
        if (uiState.phase == PinVerificationPhase.UNLOCKING_SUCCESS) {
            screenUnlockAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
            )
        } else {
            screenUnlockAlpha.snapTo(1f)
        }
    }

    val tunedPinConfig = remember(settings.refractionIntensityScale, settings.chromaticAberrationScale) {
        val rScale = settings.refractionIntensityScale.coerceIn(0.5f, 1.5f)
        val cScale = settings.chromaticAberrationScale.coerceIn(0.0f, 1.5f)
        PinGlassConfig(
            buttonSize = 82.dp,
            restingRefractionHeight = (24f * rScale).dp,
            pressedRefractionHeight = (30f * rScale).dp,
            restingRefractionAmount = (28f * rScale).dp,
            pressedRefractionAmount = (36f * rScale).dp,
            depthEffect = true,
            restingChromaticAberration = 0.18f * cScale,
            pressedChromaticAberration = 0.28f * cScale,
        )
    }

    val canAcceptInput = uiState.phase == PinVerificationPhase.IDLE_ENTERING &&
        uiState.lockoutRemainingSeconds <= 0L

    LockBackgroundRenderer.SharedRefractionBackground(
        sharedBackdrop = sharedBackdrop,
        customWallpaperUri = settings.customWallpaperUri,
        mediaProjectionBitmap = mediaProjectionBitmap,
        modifier = Modifier
            .fillMaxSize()
            .alpha(screenUnlockAlpha.value)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val activeChange = event.changes.firstOrNull { it.pressed }
                        activePointerInWindow = activeChange?.position
                    }
                }
            }
            .pointerInput(settings.biometricEnabled) {
                var accumulatedUpwardDrag = 0f
                detectVerticalDragGestures(
                    onDragStart = { accumulatedUpwardDrag = 0f },
                    onVerticalDrag = { _, dragAmount ->
                        if (dragAmount < 0f) {
                            accumulatedUpwardDrag += -dragAmount
                            if (accumulatedUpwardDrag > 140f) {
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
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Spacer(modifier = Modifier.height(36.dp))

            // HEADER & DOTS CLUSTER (Matches 96372.jpg — no Dynamic Island or clock clutter)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = uiState.statusPrompt,
                    color = if (uiState.phase == PinVerificationPhase.ERROR_SHAKE) {
                        Color(0xFFFFB4AB)
                    } else {
                        Color.White.copy(alpha = 0.94f)
                    },
                    fontSize = 20.sp,
                    lineHeight = 27.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                Spacer(modifier = Modifier.height(26.dp))

                Row(
                    modifier = Modifier.offset {
                        IntOffset(dotsShakeOffsetPx.value.roundToInt(), 0)
                    },
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
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
                                        else -> Color.White.copy(alpha = 0.88f)
                                    },
                                    shape = CircleShape,
                                ),
                        )
                    }
                }
            }

            // 3x4 LIQUID GLASS KEYPAD WITH INTERACTIVE TOUCH & DRAG GLOW
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                PIN_GRID_ROWS.forEach { rowSpecs ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(26.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        rowSpecs.forEach { spec ->
                            val rect = buttonBoundsMap[spec.digit]
                            val ptr = activePointerInWindow
                            val glowIntensity = computeProximityGlow(ptr, rect)
                            val localPtr = if (ptr != null && rect != null) {
                                Offset(ptr.x - rect.left, ptr.y - rect.top)
                            } else {
                                null
                            }

                            PinGlassRenderer.LiquidGlassPinButton(
                                backdrop = sharedBackdrop,
                                enabled = canAcceptInput,
                                isGraphicsActive = isGraphicsActive,
                                config = tunedPinConfig,
                                keyAngleOffsetDegrees = spec.lightAngleDegrees,
                                externalGlowIntensity = glowIntensity,
                                localPointerGlowOffset = localPtr,
                                onButtonPositioned = { coords ->
                                    buttonBoundsMap[spec.digit] = coords.boundsInWindow()
                                },
                                contentDescriptionText = "Số ${spec.digit}",
                                onClick = { onDigitPressed(spec.digit) },
                            ) {
                                Text(
                                    text = spec.digit.toString(),
                                    color = Color.White,
                                    fontSize = 35.sp,
                                    fontWeight = FontWeight.Normal,
                                )
                                if (spec.letters.isNotEmpty()) {
                                    Text(
                                        text = spec.letters,
                                        color = Color.White.copy(alpha = 0.85f),
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
                    val rect0 = buttonBoundsMap['0']
                    val ptr = activePointerInWindow
                    val glow0 = computeProximityGlow(ptr, rect0)
                    val localPtr0 = if (ptr != null && rect0 != null) {
                        Offset(ptr.x - rect0.left, ptr.y - rect0.top)
                    } else {
                        null
                    }

                    PinGlassRenderer.LiquidGlassPinButton(
                        backdrop = sharedBackdrop,
                        enabled = canAcceptInput,
                        isGraphicsActive = isGraphicsActive,
                        config = tunedPinConfig,
                        keyAngleOffsetDegrees = -25f,
                        externalGlowIntensity = glow0,
                        localPointerGlowOffset = localPtr0,
                        onButtonPositioned = { coords ->
                            buttonBoundsMap['0'] = coords.boundsInWindow()
                        },
                        contentDescriptionText = "Số 0",
                        onClick = { onDigitPressed('0') },
                    ) {
                        Text(
                            text = "0",
                            color = Color.White,
                            fontSize = 35.sp,
                            fontWeight = FontWeight.Normal,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Bottom row: Khẩn cấp / Khôi phục on left, Hủy / Xóa on right
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Khẩn cấp",
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

private fun computeProximityGlow(pointerInWindow: Offset?, buttonRect: Rect?): Float {
    if (pointerInWindow == null || buttonRect == null) return 0f
    val center = buttonRect.center
    val dist = hypot(pointerInWindow.x - center.x, pointerInWindow.y - center.y)
    val radius = buttonRect.width * 0.85f
    if (dist >= radius) return 0f
    return ((1f - dist / radius).coerceIn(0f, 1f))
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
                    color = Color.White.copy(alpha = 0.82f),
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
