package com.digitallock.ui.setup

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.digitallock.data.LockOperationMode
import com.digitallock.data.LockRepository
import com.digitallock.data.SettingsRepository
import com.digitallock.graphics.BackdropCaptureStore
import com.digitallock.security.AuthenticationManager
import com.digitallock.security.PinManager
import com.digitallock.security.RecoveryManager
import com.digitallock.service.MonitorService
import com.digitallock.ui.components.expressiveSpring

fun openScreenLockSettingsDirectly(context: Context) {
    val intents = listOf(
        Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD),
        Intent("android.settings.BIOMETRIC_ENROLL"),
        Intent(Settings.ACTION_SECURITY_SETTINGS),
    )
    for (intent in intents) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        } catch (_: Throwable) {
        }
    }
}

fun requestOverlayPermissionDirectly(context: Context) {
    try {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Throwable) {
    }
}

fun requestIgnoreBatteryOptimizationsDirectly(context: Context) {
    try {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Throwable) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Throwable) {
        }
    }
}

fun openAutostartAndBackgroundPermissions(context: Context) {
    val candidateIntents = listOf(
        // Xiaomi HyperOS / MIUI Autostart Management
        Intent().apply {
            component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
        },
        // Xiaomi HyperOS / MIUI App Permission Editor (Popup in background / Lockscreen display)
        Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity",
            )
            putExtra("extra_pkgname", context.packageName)
        },
        Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            putExtra("extra_pkgname", context.packageName)
        },
        // OPPO / ColorOS / Realme Startup Manager
        Intent().apply {
            component = ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            )
        },
        // Vivo Startup Manager
        Intent().apply {
            component = ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            )
        },
        // Fallback: App Info details page
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ),
    )
    for (intent in candidateIntents) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        } catch (_: Throwable) {
        }
    }
}

@Composable
fun SetupWizardScreen(
    lockRepository: LockRepository,
    settingsRepository: SettingsRepository,
    pinManager: PinManager,
    recoveryManager: RecoveryManager,
    authManager: AuthenticationManager,
    onSetupFinished: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var currentStep by remember { mutableIntStateOf(1) }
    var snapshot by remember { mutableStateOf(lockRepository.querySystemSecuritySnapshot()) }
    val isProjectionActive by BackdropCaptureStore.isMediaProjectionActive.collectAsState()

    var notificationGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationGranted = granted
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            MonitorService.initMediaProjectionSession(context, result.resultCode, result.data!!)
        }
    }

    var selectedMode by remember {
        mutableStateOf(
            if (snapshot.isAndroidDeviceSecure) {
                LockOperationMode.COEXIST_WITH_ANDROID_KEYGUARD
            } else {
                LockOperationMode.STANDALONE_SINGLE_LOCK
            }
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val updated = lockRepository.querySystemSecuritySnapshot()
                snapshot = updated
                if (!updated.isAndroidDeviceSecure) {
                    selectedMode = LockOperationMode.STANDALONE_SINGLE_LOCK
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationGranted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "Thiết lập DigitalLock • Bước $currentStep / 3",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        AnimatedContent(
            targetState = currentStep,
            transitionSpec = {
                (slideInHorizontally(animationSpec = expressiveSpring()) { it / 2 } + fadeIn()) togetherWith
                    (slideOutHorizontally(animationSpec = expressiveSpring()) { -it / 2 } + fadeOut())
            },
            label = "SetupWizardTransition",
        ) { step ->
            when (step) {
                1 -> Step1KeyguardAndPermissionsCheck(
                    snapshot = snapshot,
                    notificationGranted = notificationGranted,
                    isProjectionActive = isProjectionActive,
                    onRefreshCheck = {
                        val updated = lockRepository.querySystemSecuritySnapshot()
                        snapshot = updated
                        if (!updated.isAndroidDeviceSecure) {
                            selectedMode = LockOperationMode.STANDALONE_SINGLE_LOCK
                        }
                    },
                    onOpenScreenLockSettings = { openScreenLockSettingsDirectly(context) },
                    onRequestOverlayPermission = { requestOverlayPermissionDirectly(context) },
                    onOpenAccessibilitySettings = {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        } catch (_: Throwable) {
                        }
                    },
                    onOpenAutostartSettings = { openAutostartAndBackgroundPermissions(context) },
                    onRequestMediaProjection = {
                        val mpm = context.getSystemService(MediaProjectionManager::class.java)
                        if (mpm != null) {
                            mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
                        }
                    },
                    onRequestIgnoreBatteryOptimization = {
                        requestIgnoreBatteryOptimizationsDirectly(context)
                    },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onChooseStandalone = {
                        selectedMode = LockOperationMode.STANDALONE_SINGLE_LOCK
                        currentStep = 2
                    },
                    onChooseCoexist = {
                        selectedMode = LockOperationMode.COEXIST_WITH_ANDROID_KEYGUARD
                        currentStep = 2
                    },
                )

                2 -> Step2CreatePrimaryPin(
                    pinManager = pinManager,
                    onPinSaved = { currentStep = 3 },
                )

                3 -> Step3RecoveryConfiguration(
                    pinManager = pinManager,
                    recoveryManager = recoveryManager,
                    authManager = authManager,
                    onCompleteWizard = { enableBiometric ->
                        settingsRepository.setBiometricEnabled(enableBiometric)
                        settingsRepository.updateSetupCompleted(true, selectedMode)
                        onSetupFinished()
                    },
                )
            }
        }
    }
}

@Composable
private fun Step1KeyguardAndPermissionsCheck(
    snapshot: LockRepository.SystemSecuritySnapshot,
    notificationGranted: Boolean,
    isProjectionActive: Boolean,
    onRefreshCheck: () -> Unit,
    onOpenScreenLockSettings: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAutostartSettings: () -> Unit,
    onRequestMediaProjection: () -> Unit,
    onRequestIgnoreBatteryOptimization: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onChooseStandalone: () -> Unit,
    onChooseCoexist: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Tắt Khóa Hệ Thống & Cấp Quyền Tự Khởi Chạy",
            style = MaterialTheme.typography.headlineMedium,
        )

        ElevatedCard(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (snapshot.isAndroidDeviceSecure) {
                        "Khóa màn hình hệ thống đang BẬT"
                    } else {
                        "Khóa màn hình hệ thống đã TẮT ✓"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = if (snapshot.isAndroidDeviceSecure) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                )

                Text(
                    text = if (snapshot.isAndroidDeviceSecure) {
                        "Hãy bấm nút bên dưới để mở thẳng cài đặt Mật khẩu / Khóa màn hình và tắt khóa hệ thống (chọn Không hoặc Vuốt) để chỉ dùng 1 lớp khóa của DigitalLock."
                    } else {
                        "Bạn đã tắt khóa màn hình hệ thống! DigitalLock sẽ hoạt động làm lớp khóa PIN duy nhất."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )

                if (snapshot.isAndroidDeviceSecure) {
                    Button(
                        onClick = onOpenScreenLockSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Mở cài đặt Tắt Khóa Màn Hình Hệ Thống")
                    }

                    OutlinedButton(
                        onClick = onRefreshCheck,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Kiểm tra lại trạng thái Khóa màn hình")
                    }
                }
            }
        }

        Card(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Cấp quyền Tự bật khóa & Chụp nền màn hình",
                    style = MaterialTheme.typography.titleLarge,
                )

                OutlinedButton(
                    onClick = onRequestOverlayPermission,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (snapshot.canDrawOverlays) "1. Hiển thị trên ứng dụng khác (Tự bật khóa): Đã cấp ✓"
                        else "1. Bấm cấp quyền Hiển thị trên ứng dụng khác (Bắt buộc)"
                    )
                }

                OutlinedButton(
                    onClick = onOpenAccessibilitySettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (snapshot.isAccessibilityMonitorEnabled) "2. Dịch vụ Trợ năng DigitalLock: Đã bật ✓"
                        else "2. Bấm bật Dịch vụ Trợ năng 'DigitalLock Bảo Vệ Màn Hình Khóa'"
                    )
                }

                OutlinedButton(
                    onClick = onOpenAutostartSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("3. Bấm cấp quyền Tự khởi chạy (Autostart) & Bật cửa sổ nền")
                }

                OutlinedButton(
                    onClick = onRequestMediaProjection,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (isProjectionActive) "4. Quyền Chụp nền màn hình khóa (Android): Đã cấp ✓"
                        else "4. Bấm cấp quyền Chụp màn hình làm nền khóa (MediaProjection)"
                    )
                }

                OutlinedButton(
                    onClick = onRequestIgnoreBatteryOptimization,
                    enabled = !snapshot.isIgnoringBatteryOptimizations,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (snapshot.isIgnoringBatteryOptimizations) "5. Chạy nền không giới hạn Pin: Đã cấp ✓"
                        else "5. Bấm cho phép Chạy nền không giới hạn Pin"
                    )
                }

                if (!notificationGranted) {
                    OutlinedButton(
                        onClick = onRequestNotificationPermission,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("6. Bấm cấp quyền Thông báo dịch vụ nền")
                    }
                }
            }
        }

        if (snapshot.isAndroidDeviceSecure) {
            Button(
                onClick = onChooseCoexist,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Tiếp tục sang Bước 2 (Tạo mã PIN)")
            }
        } else {
            Button(
                onClick = onChooseStandalone,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Tiếp tục sang Bước 2 (Tạo mã PIN)")
            }
        }
    }
}

@Composable
private fun Step2CreatePrimaryPin(
    pinManager: PinManager,
    onPinSaved: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var allowAlphanumeric by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Tạo mã PIN chính",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Thiết lập mã PIN từ 4 đến 12 chữ số. Dữ liệu được băm PBKDF2-HMAC-SHA256 và mã hóa bằng Android Keystore.",
            style = MaterialTheme.typography.bodyLarge,
        )

        Card(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Cho phép mật khẩu cả chữ và số", fontWeight = FontWeight.Medium)
                    Switch(
                        checked = allowAlphanumeric,
                        onCheckedChange = {
                            allowAlphanumeric = it
                            pin = ""
                            confirmPin = ""
                        },
                    )
                }

                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 12) pin = it },
                    label = { Text("Mã PIN chính (4–12 ký tự)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (allowAlphanumeric) KeyboardType.Password else KeyboardType.NumberPassword,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { if (it.length <= 12) confirmPin = it },
                    label = { Text("Nhập lại mã PIN chính") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (allowAlphanumeric) KeyboardType.Password else KeyboardType.NumberPassword,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                statusMessage?.let { msg ->
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium,
                    )
                }

                Button(
                    onClick = {
                        when (val res = pinManager.setupPrimaryPin(pin, confirmPin, allowAlphanumeric)) {
                            is PinManager.PinSetupResult.Success -> onPinSaved()
                            is PinManager.PinSetupResult.ValidationError -> statusMessage = res.message
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Lưu mã PIN chính & Tiếp tục")
                }
            }
        }
    }
}

@Composable
private fun Step3RecoveryConfiguration(
    pinManager: PinManager,
    recoveryManager: RecoveryManager,
    authManager: AuthenticationManager,
    onCompleteWizard: (Boolean) -> Unit,
) {
    val context = LocalContext.current

    var secondaryPin by remember { mutableStateOf("") }
    var secondaryConfirm by remember { mutableStateOf("") }
    var secondaryPinSaved by remember { mutableStateOf(false) }

    val oneTimeRecoveryCode = remember {
        recoveryManager.generateAndStoreOneTimeRecoveryCode()
    }
    var recoveryCodeCopied by remember { mutableStateOf(false) }

    var securityQuestion by remember { mutableStateOf("") }
    var securityAnswer by remember { mutableStateOf("") }
    var securityQuestionSaved by remember { mutableStateOf(false) }

    val biometricSupported = remember { authManager.isStrongBiometricAvailable() }
    var enableBiometric by remember { mutableStateOf(biometricSupported) }

    var feedbackMessage by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Thiết lập Khôi phục & Sinh trắc học",
            style = MaterialTheme.typography.headlineMedium,
        )

        ElevatedCard(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Mã khôi phục 1 lần (Chỉ hiển thị duy nhất lúc này)",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = oneTimeRecoveryCode,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Hãy sao chép và lưu lại mã này. Hệ thống chỉ lưu bản băm đã mã hóa.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("DigitalLock Recovery Code", oneTimeRecoveryCode))
                        recoveryCodeCopied = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (recoveryCodeCopied) "Đã sao chép Mã khôi phục ✓" else "Sao chép Mã khôi phục")
                }
            }
        }

        Card(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Mã PIN phụ độc lập (Khác với mã PIN chính)",
                    style = MaterialTheme.typography.titleLarge,
                )
                OutlinedTextField(
                    value = secondaryPin,
                    onValueChange = { if (it.length <= 12) secondaryPin = it },
                    label = { Text("Mã PIN phụ (4–12 chữ số)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = secondaryConfirm,
                    onValueChange = { if (it.length <= 12) secondaryConfirm = it },
                    label = { Text("Xác nhận mã PIN phụ") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        when (val res = pinManager.setupSecondaryPin(secondaryPin, secondaryConfirm)) {
                            is PinManager.PinSetupResult.Success -> {
                                secondaryPinSaved = true
                                feedbackMessage = "Đã lưu mã PIN phụ thành công."
                            }
                            is PinManager.PinSetupResult.ValidationError -> {
                                feedbackMessage = res.message
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (secondaryPinSaved) "Đã lưu mã PIN phụ ✓" else "Lưu mã PIN phụ")
                }
            }
        }

        Card(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Câu hỏi bảo mật (Tùy chọn)",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = RecoveryManager.SECURITY_QUESTION_ENTROPY_WARNING,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                )
                OutlinedTextField(
                    value = securityQuestion,
                    onValueChange = { securityQuestion = it },
                    label = { Text("Nhập câu hỏi bảo mật của bạn") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = securityAnswer,
                    onValueChange = { securityAnswer = it },
                    label = { Text("Nhập câu trả lời") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        if (recoveryManager.setupSecurityQuestion(securityQuestion, securityAnswer)) {
                            securityQuestionSaved = true
                            feedbackMessage = "Đã chuẩn hóa và lưu câu hỏi bảo mật."
                        } else {
                            feedbackMessage = "Câu hỏi (tối thiểu 4 ký tự) và câu trả lời (tối thiểu 2 ký tự) không hợp lệ."
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (securityQuestionSaved) "Đã lưu câu hỏi bảo mật ✓" else "Lưu câu hỏi bảo mật")
                }
            }
        }

        Card(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Mở khóa bằng Vân tay / Sinh trắc học", fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (biometricSupported) {
                            "Sử dụng xác thực phần cứng BiometricPrompt của Android."
                        } else {
                            "Thiết bị chưa đăng ký vân tay hoặc sinh trắc học mạnh."
                        },
                    )
                }
                Switch(
                    checked = enableBiometric && biometricSupported,
                    enabled = biometricSupported,
                    onCheckedChange = { enableBiometric = it },
                )
            }
        }

        feedbackMessage?.let {
            Text(text = it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        }

        Spacer(modifier = Modifier.height(4.dp))

        Button(
            onClick = { onCompleteWizard(enableBiometric && biometricSupported) },
            enabled = secondaryPinSaved || recoveryCodeCopied,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Hoàn tất thiết lập & Bật DigitalLock")
        }
    }
}
