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

/**
 * Material 3 Expressive Dashboard for DigitalLock (UI System A).
 * Displays live security status, Android platform boundary diagnostics, Photo Picker
 * background customization, optical refraction tuning, and system permission shortcuts.
 */
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
            text = "DigitalLock Control Center",
            style = MaterialTheme.typography.headlineLarge,
        )

        // Primary Lock Action Card
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
                            text = if (settings.lockEnabled) "DigitalLock Protection Active" else "DigitalLock Paused",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = if (settings.operationMode == LockOperationMode.STANDALONE_SINGLE_LOCK) {
                                "Mode: Standalone Single-PIN Lock"
                            } else {
                                "Mode: Coexisting with Android Keyguard"
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
                    Text("Launch Liquid Glass PIN Lock Screen Now")
                }
            }
        }

        // Honest Android System Capabilities & Permissions Card
        Card(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Android System Integration & Honest Boundaries",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "• Android System Keyguard Active: ${if (snapshot.isAndroidDeviceSecure) "Yes (Double-lock unless disabled in Android Settings)" else "No (Single-PIN mode ready)"}
" +
                        "• Accessibility Window Monitor: ${if (snapshot.isAccessibilityMonitorEnabled) "Enabled (Assists background lock transitions)" else "Disabled (Android 10–14+ restricts background Activity starts)"}
" +
                        "• Managed Device Owner (Lock Task Mode): ${if (snapshot.isDeviceOwnerProvisioned) "Provisioned (Hardware Kiosk Lock Active)" else "Standard App Mode (Not Device Owner)"}
" +
                        "• Battery Optimization Unrestricted: ${if (snapshot.isIgnoringBatteryOptimizations) "Yes" else "No"}
" +
                        "• Last Launch Diagnostic: ${lastDiag.status} — ${lastDiag.reasonMessage}",
                    style = MaterialTheme.typography.bodyLarge,
                )

                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Configure AccessibilityMonitor (Optional Consent)")
                }

                if (!snapshot.isIgnoringBatteryOptimizations) {
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open Battery Optimization Settings")
                    }
                }

                if (snapshot.isHyperOsOrMiui) {
                    Text(
                        text = "Xiaomi HyperOS / MIUI Detected: Enable 'Autostart' and set Battery Saver to 'No restrictions' in App Info so MonitorService is not killed when the screen turns off.",
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // Liquid Glass Background & Optical Tuning Card
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
                    text = "Liquid Glass Backdrop & Lens Calibration",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = if (settings.customWallpaperUri != null) {
                        "Active Backdrop: User Selected Photo Picker Texture"
                    } else {
                        "Active Backdrop: High-Depth Procedural iOS 27 Blue-Teal Waves"
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
                        Text("Pick Photo Backdrop")
                    }
                    if (settings.customWallpaperUri != null) {
                        OutlinedButton(
                            onClick = { settingsRepository.setCustomWallpaperUri(null) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Reset to Procedural")
                        }
                    }
                }

                Text("Lens Refraction Scale: ${(refScale * 100).toInt()}%")
                Slider(
                    value = refScale,
                    onValueChange = {
                        refScale = it
                        settingsRepository.setOpticalTuning(refScale, chrScale)
                    },
                    valueRange = 0.6f..1.4f,
                )

                Text("Rim Chromatic Dispersion Scale: ${(chrScale * 100).toInt()}%")
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

        // Credentials & Recovery Status Card
        Card(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Credential & Recovery Status",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "• Primary PIN Configured: ${pinManager.isPrimaryPinConfigured()} (${pinManager.getExpectedPinLength()} chars)
" +
                        "• Secondary Fallback PIN: ${pinManager.isSecondaryPinConfigured()}
" +
                        "• Salted Recovery Code Hash: ${recoveryManager.hasRecoveryCode()}
" +
                        "• Salted Security Question: ${recoveryManager.hasSecurityQuestion()}",
                )
                OutlinedButton(
                    onClick = onReopenSetupWizard,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Re-run Setup Wizard / Rotate PIN & Recovery Credentials")
                }
            }
        }
    }
}
