package com.digitallock.ui.home

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.digitallock.data.LockOperationMode
import com.digitallock.data.LockRepository
import com.digitallock.data.SettingsRepository
import com.digitallock.lock.LockActivity
import com.digitallock.security.PinManager
import com.digitallock.security.RecoveryManager
import com.digitallock.service.MonitorService
import com.digitallock.ui.setup.openHyperOsAutostartAndPermissions
import com.digitallock.ui.setup.openScreenLockSettingsDirectly
import com.digitallock.ui.setup.requestIgnoreBatteryOptimizationsDirectly

@Composable
fun HomeScreen(
    settingsRepository: SettingsRepository,
    lockRepository: LockRepository,
    pinManager: PinManager,
    recoveryManager: RecoveryManager,
    onReopenSetupWizard: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val settings by settingsRepository.settingsFlow.collectAsState()
    val lastDiag by lockRepository.lastDiagnostic.collectAsState()
    var snapshot by remember { mutableStateOf(lockRepository.querySystemSecuritySnapshot()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                snapshot = lockRepository.querySystemSecuritySnapshot()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
            }
            settingsRepository.setCustomWallpaperUri(uri.toString())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "Trung tâm điều khiển DigitalLock",
            style = MaterialTheme.typography.headlineLarge,
        )

        ElevatedCard(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (settings.lockEnabled) "DigitalLock đang BẬT" else "DigitalLock đang TẠM DỪNG",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = if (settings.operationMode == LockOperationMode.STANDALONE_SINGLE_LOCK) {
                                "Chế độ: Khóa PIN độc lập (1 lớp khóa)"
                            } else {
                                "Chế độ: Song song với khóa màn hình Android"
                            },
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Switch(
                        checked = settings.lockEnabled,
                        onCheckedChange = { enabled ->
                            settingsRepository.setLockEnabled(enabled)
                            if (enabled) MonitorService.startIfEnabled(context)
                        },
                    )
                }

                Button(
                    onClick = {
                        context.startActivity(
                            Intent(context, LockActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Mở màn hình khóa Liquid Glass PIN ngay")
                }
            }
        }

        Card(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Trạng thái hệ thống & Cấp quyền",
                    style = MaterialTheme.typography.titleLarge,
                )
                val keyguardStatusText = if (snapshot.isAndroidDeviceSecure) {
                    "Đang bật (Hãy tắt trong cài đặt Khóa màn hình để tránh nhập 2 lần PIN)"
                } else {
                    "Đã tắt (Chuẩn chế độ 1 lớp khóa)"
                }
                val accessibilityStatusText = if (snapshot.isAccessibilityMonitorEnabled) {
                    "Đã bật ✓"
                } else {
                    "Chưa bật (Cần bật để tự mở màn hình khóa khi bật máy)"
                }
                val deviceOwnerStatusText = if (snapshot.isDeviceOwnerProvisioned) {
                    "Đã kích hoạt Device Owner ✓"
                } else {
                    "Chế độ ứng dụng tiêu chuẩn"
                }
                val batteryStatusText = if (snapshot.isIgnoringBatteryOptimizations) "Đã cho phép ✓" else "Chưa cấp quyền"
                Text(
                    text = listOf(
                        "• Khóa màn hình hệ thống: $keyguardStatusText",
                        "• Dịch vụ Trợ năng DigitalLock: $accessibilityStatusText",
                        "• Không giới hạn tối ưu hóa Pin: $batteryStatusText",
                        "• Chế độ Quản trị (Device Owner): $deviceOwnerStatusText",
                        "• Trạng thái gần nhất: ${lastDiag.status}",
                    ).joinToString(separator = "\n"),
                    style = MaterialTheme.typography.bodyLarge,
                )

                if (snapshot.isAndroidDeviceSecure) {
                    Button(
                        onClick = { openScreenLockSettingsDirectly(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Mở thẳng cài đặt Tắt Khóa Màn Hình Hệ Thống")
                    }
                }

                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (snapshot.isAccessibilityMonitorEnabled) "Cài đặt Trợ năng (Đã bật ✓)"
                        else "Bật Dịch vụ Trợ năng DigitalLock"
                    )
                }

                if (!snapshot.isIgnoringBatteryOptimizations) {
                    OutlinedButton(
                        onClick = { requestIgnoreBatteryOptimizationsDirectly(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Cho phép chạy nền không giới hạn Pin")
                    }
                }

                if (snapshot.isHyperOsOrMiui) {
                    OutlinedButton(
                        onClick = { openHyperOsAutostartAndPermissions(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Mở quyền Tự khởi chạy & Hiển thị trên màn hình khóa (HyperOS)")
                    }
                }
            }
        }

        Card(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            var refScale by remember(settings.refractionIntensityScale) {
                mutableFloatStateOf(settings.refractionIntensityScale)
            }
            var chrScale by remember(settings.chromaticAberrationScale) {
                mutableFloatStateOf(settings.chromaticAberrationScale)
            }

            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Hình nền & Tinh chỉnh thấu kính Liquid Glass",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = if (settings.customWallpaperUri != null) {
                        "Nguồn nền: Ảnh tùy chỉnh từ thư viện của bạn"
                    } else {
                        "Nguồn nền: Nền sóng Liquid Glass chiều sâu mặc định"
                    },
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Chọn ảnh nền khóa")
                    }
                    if (settings.customWallpaperUri != null) {
                        OutlinedButton(
                            onClick = { settingsRepository.setCustomWallpaperUri(null) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Về nền mặc định")
                        }
                    }
                }

                Text("Cường độ khúc xạ ánh sáng: ${(refScale * 100).toInt()}%")
                Slider(
                    value = refScale,
                    onValueChange = {
                        refScale = it
                        settingsRepository.setOpticalTuning(refScale, chrScale)
                    },
                    valueRange = 0.6f..1.4f,
                )

                Text("Độ tán sắc viền kính (Chromatic Dispersion): ${(chrScale * 100).toInt()}%")
                Slider(
                    value = chrScale,
                    onValueChange = {
                        chrScale = it
                        settingsRepository.setOpticalTuning(refScale, chrScale)
                    },
                    valueRange = 0.0f..1.4f,
                )
            }
        }

        Card(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Quản lý mã PIN & Khôi phục",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = listOf(
                        "• Mã PIN chính: Đã thiết lập (${pinManager.getExpectedPinLength()} ký tự)",
                        "• Mã PIN phụ: ${if (pinManager.isSecondaryPinConfigured()) "Đã thiết lập ✓" else "Chưa thiết lập"}",
                        "• Mã khôi phục 1 lần: ${if (recoveryManager.hasRecoveryCode()) "Đã lưu bản băm ✓" else "Chưa tạo"}",
                        "• Câu hỏi bảo mật: ${if (recoveryManager.hasSecurityQuestion()) "Đã thiết lập ✓" else "Chưa thiết lập"}",
                    ).joinToString(separator = "\n"),
                )
                OutlinedButton(
                    onClick = onReopenSetupWizard,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Mở lại Trình thiết lập PIN & Khôi phục")
                }
            }
        }
    }
}
