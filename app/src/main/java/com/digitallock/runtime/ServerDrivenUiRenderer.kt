package com.digitallock.runtime

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.digitallock.cache.LastKnownGoodCacheManager
import com.digitallock.data.LockOperationMode
import com.digitallock.data.LockRepository
import com.digitallock.data.SettingsRepository
import com.digitallock.lock.LockController
import com.digitallock.security.IntegrityCoordinator
import com.digitallock.security.PinManager
import com.digitallock.security.RecoveryManager
import com.digitallock.security.SafeRecoveryGuard
import com.digitallock.security.ServerLicenseManager
import com.digitallock.service.MonitorService
import com.digitallock.ui.components.DigitalLockExpressiveTheme
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Native Jetpack Compose Server-Driven UI (SDUI) Renderer.
 *
 * Renders 100% native Material 3 Expressive UI driven entirely by the signed, AES-GCM encrypted
 * `ui_layout.json` downloaded from the PHP Backend — zero WebView webpage look, zero hardcoded
 * offline substitute UI, and instant remote updates from the Admin Panel without rebuilding APK.
 */
@Composable
fun ServerDrivenNativeUi(
    cacheManager: LastKnownGoodCacheManager,
    settingsRepository: SettingsRepository,
    lockRepository: LockRepository,
    pinManager: PinManager,
    recoveryManager: RecoveryManager,
    licenseManager: ServerLicenseManager,
    integrityCoordinator: IntegrityCoordinator,
    onRequestSync: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings by settingsRepository.settingsFlow.collectAsState()
    var snapshot by remember { mutableStateOf(lockRepository.querySystemSecuritySnapshot()) }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                snapshot = lockRepository.querySystemSecuritySnapshot()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val layoutJson = remember(cacheManager.getActiveVersionCode()) {
        val bytes = cacheManager.readVerifiedDecryptedAsset("ui_layout.json")
        if (bytes != null) {
            try {
                JSONObject(String(bytes, StandardCharsets.UTF_8))
            } catch (_: Throwable) {
                JSONObject()
            }
        } else {
            JSONObject()
        }
    }

    val tabsArray = layoutJson.optJSONArray("tabs") ?: JSONArray()
    var selectedTab by remember { mutableIntStateOf(0) }

    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var pinFeedback by remember { mutableStateOf<String?>(null) }
    var recoveryCode by remember { mutableStateOf<String?>(null) }

    DigitalLockExpressiveTheme(
        dynamicColor = settings.dynamicColorEnabled,
        darkThemeOverride = settings.darkThemePreference,
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
            bottomBar = {
                if (tabsArray.length() > 0) {
                    NavigationBar {
                        for (i in 0 until tabsArray.length()) {
                            val tab = tabsArray.optJSONObject(i) ?: continue
                            val id = tab.optString("id", "tab_$i")
                            val title = tab.optString("title", id)
                            val icon = when (id) {
                                "pin" -> Icons.Rounded.Pin
                                "glass" -> Icons.Rounded.Palette
                                "system" -> Icons.Rounded.Shield
                                else -> Icons.Rounded.Lock
                            }
                            NavigationBarItem(
                                selected = selectedTab == i,
                                onClick = { selectedTab = i },
                                icon = { Icon(icon, contentDescription = title) },
                                label = { Text(title) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Header driven by Backend layout
                val appTitle = layoutJson.optString("app_title", "DIGITALLOCK")
                Text(
                    text = appTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "UI v${cacheManager.getActiveVersionCode()} • Gói ${licenseManager.currentPlan().uppercase()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val currentTabObj = tabsArray.optJSONObject(selectedTab.coerceIn(0, (tabsArray.length() - 1).coerceAtLeast(0)))
                val cards = currentTabObj?.optJSONArray("cards") ?: JSONArray()

                for (cIdx in 0 until cards.length()) {
                    val card = cards.optJSONObject(cIdx) ?: continue
                    val cardType = card.optString("type", "info")
                    val cardTitle = card.optString("title", "")
                    val cardSubtitle = card.optString("subtitle", "")

                    when (cardType) {
                        "lock_control" -> {
                            ElevatedCard(
                                shape = MaterialTheme.shapes.extraLarge,
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
                                            Text(cardTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                            if (cardSubtitle.isNotBlank()) {
                                                Text(cardSubtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        Switch(
                                            checked = settings.lockEnabled,
                                            enabled = pinManager.isPrimaryPinConfigured(),
                                            onCheckedChange = { enabled ->
                                                settingsRepository.updateSetupCompleted(true, LockOperationMode.STANDALONE_SINGLE_LOCK)
                                                settingsRepository.setLockEnabled(enabled)
                                                if (enabled) MonitorService.startIfEnabled(context)
                                            },
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Button(
                                            onClick = {
                                                if (pinManager.isPrimaryPinConfigured()) {
                                                    settingsRepository.updateSetupCompleted(true, LockOperationMode.STANDALONE_SINGLE_LOCK)
                                                    LockController(context, settingsRepository, lockRepository)
                                                        .requestLockScreen("NativeSduiLockNow")
                                                } else {
                                                    pinFeedback = "Vui lòng thiết lập mã PIN ở tab Mã PIN trước."
                                                    selectedTab = 1
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Text(card.optString("primary_button", "Khóa ngay"))
                                        }
                                        OutlinedButton(
                                            onClick = onRequestSync,
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Text(card.optString("secondary_button", "Cập nhật UI"))
                                        }
                                    }
                                }
                            }
                        }

                        "permissions_status" -> {
                            Card(
                                shape = MaterialTheme.shapes.large,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(cardTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    StatusLine("Dịch vụ Trợ năng chống thoát", snapshot.isAccessibilityMonitorEnabled)
                                    StatusLine("Hiển thị trên ứng dụng khác", snapshot.canDrawOverlays)
                                    StatusLine("Bỏ qua tối ưu pin", snapshot.isIgnoringBatteryOptimizations)
                                    StatusLine("Mã PIN chính", pinManager.isPrimaryPinConfigured())

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                            },
                                            modifier = Modifier.weight(1f),
                                        ) { Text("Trợ năng") }
                                        OutlinedButton(
                                            onClick = {
                                                context.startActivity(
                                                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                )
                                            },
                                            modifier = Modifier.weight(1f),
                                        ) { Text("Overlay") }
                                    }
                                }
                            }
                        }

                        "pin_setup" -> {
                            Card(
                                shape = MaterialTheme.shapes.large,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(cardTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    if (cardSubtitle.isNotBlank()) {
                                        Text(cardSubtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    OutlinedTextField(
                                        value = newPin,
                                        onValueChange = { if (it.length <= 12) newPin = it },
                                        label = { Text("Mã PIN mới (4–12 chữ số)") },
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    OutlinedTextField(
                                        value = confirmPin,
                                        onValueChange = { if (it.length <= 12) confirmPin = it },
                                        label = { Text("Xác nhận mã PIN") },
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Button(
                                        onClick = {
                                            when (val res = pinManager.setupPrimaryPin(newPin, confirmPin, false)) {
                                                is PinManager.PinSetupResult.Success -> {
                                                    settingsRepository.updateSetupCompleted(true, LockOperationMode.STANDALONE_SINGLE_LOCK)
                                                    MonitorService.startIfEnabled(context)
                                                    newPin = ""
                                                    confirmPin = ""
                                                    pinFeedback = "Đã lưu mã PIN an toàn vào Android Keystore."
                                                }
                                                is PinManager.PinSetupResult.ValidationError -> {
                                                    pinFeedback = res.message
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(card.optString("button_text", "Lưu mã PIN"))
                                    }
                                    pinFeedback?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }

                        "recovery_code" -> {
                            Card(
                                shape = MaterialTheme.shapes.large,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(cardTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    if (cardSubtitle.isNotBlank()) {
                                        Text(cardSubtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    OutlinedButton(
                                        onClick = { recoveryCode = recoveryManager.generateAndStoreOneTimeRecoveryCode() },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(card.optString("button_text", "Tạo mã khôi phục"))
                                    }
                                    recoveryCode?.let {
                                        Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        "liquid_glass_tuning" -> {
                            Card(
                                shape = MaterialTheme.shapes.large,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(cardTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text("Cường độ khúc xạ: ${"%.2f".format(settings.refractionIntensityScale)}")
                                    Slider(
                                        value = settings.refractionIntensityScale,
                                        onValueChange = { settingsRepository.setOpticalTuning(it, settings.chromaticAberrationScale) },
                                        valueRange = 0.5f..1.5f,
                                    )
                                    Text("Tán sắc sắc sai: ${"%.2f".format(settings.chromaticAberrationScale)}")
                                    Slider(
                                        value = settings.chromaticAberrationScale,
                                        onValueChange = { settingsRepository.setOpticalTuning(settings.refractionIntensityScale, it) },
                                        valueRange = 0.0f..1.5f,
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("Chặn chụp màn hình khóa", modifier = Modifier.weight(1f))
                                        Switch(
                                            checked = settings.preventScreenshotsOnLock,
                                            onCheckedChange = { settingsRepository.setPreventScreenshotsOnLock(it) },
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("Mở khóa bằng Sinh trắc học", modifier = Modifier.weight(1f))
                                        Switch(
                                            checked = settings.biometricEnabled,
                                            onCheckedChange = { settingsRepository.setBiometricEnabled(it) },
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("Material 3 Dynamic Color", modifier = Modifier.weight(1f))
                                        Switch(
                                            checked = settings.dynamicColorEnabled,
                                            onCheckedChange = { settingsRepository.setDynamicColorEnabled(it) },
                                        )
                                    }
                                }
                            }
                        }

                        "integrity_summary" -> {
                            val report = remember { integrityCoordinator.runLocalIntegrityCheck() }
                            Card(
                                shape = MaterialTheme.shapes.large,
                                colors = CardDefaults.cardColors(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(cardTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    StatusLine("Rust Security Engine", report.nativeRustSecurityLoaded)
                                    StatusLine("C++20 Liquid Glass Engine", report.nativeGlassLoaded)
                                    StatusLine("Last Known Good Cache (AES-GCM)", report.localCacheHealthy)
                                    StatusLine("Chế độ an toàn bình thường", !SafeRecoveryGuard.isSafeRecoveryMode(context))
                                }
                            }
                        }

                        else -> {
                            Card(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Text(cardTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    if (cardSubtitle.isNotBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(cardSubtitle, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = if (ok) "Sẵn sàng" else "Chưa bật",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}
