package com.digitallock.ui.launch

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.digitallock.remote.ServerSyncRepository
import com.digitallock.remote.SyncStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Minimal Branded DIGITALLOCK Bootstrap & License Gate Screen.
 *
 * - Displays logo, smooth entrance animation, real-time 0–100% progress bar, and user-friendly Vietnamese status.
 * - Zero debug info (no URLs, HTTP codes, hashes, or stack traces).
 * - Requires Server-Verified License Activation + UI Bundle download on first launch; never acts as an offline substitute app.
 */
@Composable
fun AppLaunchScreen(
    syncRepository: ServerSyncRepository,
    onReadyToProceed: () -> Unit,
) {
    val progressState by syncRepository.progress.collectAsState()
    val scope = rememberCoroutineScope()
    var retryCounter by remember { mutableIntStateOf(0) }
    var licenseKeyInput by remember { mutableStateOf("") }

    val logoScale = remember { Animatable(0.84f) }
    val logoAlpha = remember { Animatable(0f) }
    val exitAlpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        logoAlpha.animateTo(1f, animationSpec = tween(420, easing = FastOutSlowInEasing))
        logoScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
        )
    }

    LaunchedEffect(retryCounter) {
        val result = syncRepository.runLaunchSync()
        if (result.status == SyncStatus.COMPLETED_ONLINE ||
            result.status == SyncStatus.COMPLETED_OFFLINE_LKG
        ) {
            delay(240L)
            exitAlpha.animateTo(0f, animationSpec = tween(200, easing = FastOutSlowInEasing))
            onReadyToProceed()
        }
    }

    val animatedProgress by animateFloatAsState(
        targetValue = (progressState.percent / 100f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "launch_progress",
    )

    val isError = progressState.status == SyncStatus.ERROR_NO_INTERNET ||
        progressState.status == SyncStatus.ERROR_SERVER_UNREACHABLE ||
        progressState.status == SyncStatus.ERROR_DATA_FAILED

    val needsLicense = progressState.status == SyncStatus.NEEDS_LICENSE_ACTIVATION

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(exitAlpha.value)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0A0D14), Color(0xFF111827), Color(0xFF090C12))
                )
            )
            .systemBarsPadding()
            .padding(horizontal = 32.dp, vertical = 36.dp),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .scale(logoScale.value)
                    .alpha(logoAlpha.value)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(1.2.dp, Color.White.copy(alpha = 0.24f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when {
                        progressState.status == SyncStatus.ERROR_NO_INTERNET -> Icons.Rounded.WifiOff
                        needsLicense -> Icons.Rounded.Key
                        else -> Icons.Rounded.Lock
                    },
                    contentDescription = "DIGITALLOCK",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp),
                )
            }

            Text(
                text = "DIGITALLOCK",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 3.sp,
                modifier = Modifier.alpha(logoAlpha.value),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = progressState.statusMessage,
                color = if (isError) Color(0xFFFFB4AB) else Color.White.copy(alpha = 0.88f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )

            when {
                needsLicense -> {
                    OutlinedTextField(
                        value = licenseKeyInput,
                        onValueChange = { licenseKeyInput = it },
                        placeholder = { Text("Nhập mã bản quyền (DL-XXXXXX-...)", color = Color.White.copy(alpha = 0.45f)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.White,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.35f),
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                val res = syncRepository.activateAndSync(licenseKeyInput)
                                if (res.status == SyncStatus.COMPLETED_ONLINE ||
                                    res.status == SyncStatus.COMPLETED_OFFLINE_LKG
                                ) {
                                    delay(240L)
                                    onReadyToProceed()
                                }
                            }
                        },
                        enabled = licenseKeyInput.trim().length >= 12,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color(0xFF0A0D14),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Kích hoạt bản quyền", fontWeight = FontWeight.SemiBold)
                    }
                }

                isError -> {
                    Text(
                        text = "Vui lòng kiểm tra kết nối Internet và thử lại để hoàn tất khởi tạo DIGITALLOCK.",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = { retryCounter++ },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color(0xFF0A0D14),
                        ),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Thử lại", fontWeight = FontWeight.SemiBold)
                    }
                }

                else -> {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.16f),
                        strokeCap = StrokeCap.Round,
                    )
                    Text(
                        text = "${progressState.percent}%",
                        color = Color.White.copy(alpha = 0.68f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
