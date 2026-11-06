package com.digitallock.ui.setup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.digitallock.data.LockOperationMode
import com.digitallock.data.LockRepository
import com.digitallock.data.SettingsRepository
import com.digitallock.security.AuthenticationManager
import com.digitallock.security.PinManager
import com.digitallock.security.RecoveryManager
import com.digitallock.ui.components.expressiveSpring

/**
 * 3-Step Material 3 Expressive Setup Wizard:
 * - Step 1: Android System Lock Inspection via `KeyguardManager.isDeviceSecure`.
 *           Offers Standalone Single-Lock Mode (guiding user to Android Security Settings so
 *           they do not enter two PINs in a row) vs. Coexist with Android Keyguard Mode.
 * - Step 2: Primary PIN creation (4–12 digits or alphanumeric, confirmation, PBKDF2 + Keystore).
 * - Step 3: Independent Recovery setup (Secondary PIN != Primary PIN, one-time copyable
 *           CSPRNG Recovery Code, normalized Security Question with entropy warning, and
 *           opt-in Class 3 Biometric toggle).
 */
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
    var selectedMode by remember {
        mutableStateOf(
            if (snapshot.isAndroidDeviceSecure) {
                LockOperationMode.COEXIST_WITH_ANDROID_KEYGUARD
            } else {
                LockOperationMode.STANDALONE_SINGLE_LOCK
            }
        )
    }

    // Re-check KeyguardManager automatically when user returns from Android Security Settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val updated = lockRepository.querySystemSecuritySnapshot()
                snapshot = updated
                if (!updated.isAndroidDeviceSecure) {
                    selectedMode = LockOperationMode.STANDALONE_SINGLE_LOCK
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "DigitalLock Setup • Step $currentStep of 3",
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
                1 -> Step1KeyguardCheck(
                    snapshot = snapshot,
                    onRefreshCheck = {
                        val updated = lockRepository.querySystemSecuritySnapshot()
                        snapshot = updated
                        if (!updated.isAndroidDeviceSecure) {
                            selectedMode = LockOperationMode.STANDALONE_SINGLE_LOCK
                        }
                    },
                    onOpenAndroidSecuritySettings = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                        } catch (_: Throwable) {
                            context.startActivity(Intent(Settings.ACTION_SETTINGS))
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
private fun Step1KeyguardCheck(
    snapshot: LockRepository.SystemSecuritySnapshot,
    onRefreshCheck: () -> Unit,
    onOpenAndroidSecuritySettings: () -> Unit,
    onChooseStandalone: () -> Unit,
    onChooseCoexist: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "System Lock Inspection",
            style = MaterialTheme.typography.headlineLarge,
        )

        ElevatedCard(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = if (snapshot.isAndroidDeviceSecure) {
                        "Android System Lock is Currently Active"
                    } else {
                        "Android System Lock is Disabled (Ready for Single-PIN Mode)"
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
                        "KeyguardManager detected an active system credential (PIN, Password, or Pattern). Standard Android security rules prohibit third-party apps from automatically disabling or replacing the system Keyguard."
                    } else {
                        "KeyguardManager verified that no system PIN, Password, or Pattern is active. You can use DigitalLock as your sole PIN prompt without entering two passwords in a row."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )

                if (snapshot.isAndroidDeviceSecure) {
                    OutlinedButton(
                        onClick = onOpenAndroidSecuritySettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("1. Open Android Security Settings to Turn Off System Lock")
                    }

                    OutlinedButton(
                        onClick = onRefreshCheck,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("2. Re-check KeyguardManager Status")
                    }

                    Button(
                        onClick = onChooseCoexist,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Keep Android System Lock (Disable Single-Lock Mode)")
                    }
                } else {
                    Button(
                        onClick = onChooseStandalone,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Continue in Independent Single-PIN Mode")
                    }
                }
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
            text = "Create Primary PIN",
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            text = "Choose a 4 to 12 character passcode. Stored exclusively as a 210,000-iteration PBKDF2-HMAC-SHA256 hash encrypted inside Android Keystore.",
            style = MaterialTheme.typography.bodyLarge,
        )

        Card(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Allow Alphanumeric Passcode", fontWeight = FontWeight.Medium)
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
                    label = { Text("Primary PIN (4–12 chars)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (allowAlphanumeric) KeyboardType.Password else KeyboardType.NumberPassword,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { if (it.length <= 12) confirmPin = it },
                    label = { Text("Confirm Primary PIN") },
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
                    Text("Verify & Save Primary PIN")
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

    // Generate the one-time recovery code once on entering Step 3
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
            text = "Configure Recovery & Biometrics",
            style = MaterialTheme.typography.headlineLarge,
        )

        // 1. One-Time Recovery Code Card
        ElevatedCard(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "One-Time Recovery Code (Displayed Only Once)",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = oneTimeRecoveryCode,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Save this code in a safe place now. Only its salted PBKDF2-HMAC-SHA256 hash is stored on the device.",
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
                    Text(if (recoveryCodeCopied) "Recovery Code Copied" else "Copy Recovery Code")
                }
            }
        }

        // 2. Independent Secondary PIN Card
        Card(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Secondary Fallback PIN (Must Differ from Primary PIN)",
                    style = MaterialTheme.typography.titleLarge,
                )
                OutlinedTextField(
                    value = secondaryPin,
                    onValueChange = { if (it.length <= 12) secondaryPin = it },
                    label = { Text("Secondary PIN (4–12 digits)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = secondaryConfirm,
                    onValueChange = { if (it.length <= 12) secondaryConfirm = it },
                    label = { Text("Confirm Secondary PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        when (val res = pinManager.setupSecondaryPin(secondaryPin, secondaryConfirm)) {
                            is PinManager.PinSetupResult.Success -> {
                                secondaryPinSaved = true
                                feedbackMessage = "Secondary PIN saved independently."
                            }
                            is PinManager.PinSetupResult.ValidationError -> {
                                feedbackMessage = res.message
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (secondaryPinSaved) "Secondary PIN Configured ✓" else "Save Secondary PIN")
                }
            }
        }

        // 3. Security Question Card (with lower-security warning)
        Card(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Security Question (Optional)",
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
                    label = { Text("Custom Security Question") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = securityAnswer,
                    onValueChange = { securityAnswer = it },
                    label = { Text("Answer (Normalized & Hashed with Salt)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        if (recoveryManager.setupSecurityQuestion(securityQuestion, securityAnswer)) {
                            securityQuestionSaved = true
                            feedbackMessage = "Security Question normalized and hashed."
                        } else {
                            feedbackMessage = "Question (min 4 chars) and Answer (min 2 chars) required."
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (securityQuestionSaved) "Security Question Saved ✓" else "Save Security Question")
                }
            }
        }

        // 4. Biometric Authentication Card
        Card(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Biometric Unlock (Class 3 Strong)", fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (biometricSupported) {
                            "Uses Android BiometricPrompt hardware verification."
                        } else {
                            "No Class 3 biometric sensor/enrollment detected on this device."
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
            Text("Complete Setup & Activate DigitalLock")
        }
    }
}
