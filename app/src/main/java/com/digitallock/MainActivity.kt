package com.digitallock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.digitallock.data.LockRepository
import com.digitallock.data.SettingsRepository
import com.digitallock.security.AuthenticationManager
import com.digitallock.security.CredentialStore
import com.digitallock.security.PinManager
import com.digitallock.security.RecoveryManager
import com.digitallock.service.MonitorService
import com.digitallock.ui.components.DigitalLockExpressiveTheme
import com.digitallock.ui.home.HomeScreen
import com.digitallock.ui.setup.SetupWizardScreen

/**
 * Main Material 3 Expressive entry point for configuring DigitalLock, running the Setup Wizard,
 * inspecting Android system lock boundaries, and customizing the Liquid Glass PIN Engine.
 */
class MainActivity : ComponentActivity() {

    private lateinit var credentialStore: CredentialStore
    private lateinit var pinManager: PinManager
    private lateinit var recoveryManager: RecoveryManager
    private lateinit var authManager: AuthenticationManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var lockRepository: LockRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        credentialStore = CredentialStore(this)
        pinManager = PinManager(credentialStore)
        recoveryManager = RecoveryManager(credentialStore)
        authManager = AuthenticationManager(this, credentialStore, pinManager, recoveryManager)
        settingsRepository = SettingsRepository(this)
        lockRepository = LockRepository(this)

        MonitorService.startIfEnabled(this)

        setContent {
            val settings by settingsRepository.settingsFlow.collectAsState()
            var forceWizard by remember { mutableStateOf(false) }

            DigitalLockExpressiveTheme(
                dynamicColor = settings.dynamicColorEnabled,
                darkThemeOverride = settings.darkThemePreference,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    if (!settings.setupCompleted || !pinManager.isPrimaryPinConfigured() || forceWizard) {
                        SetupWizardScreen(
                            lockRepository = lockRepository,
                            settingsRepository = settingsRepository,
                            pinManager = pinManager,
                            recoveryManager = recoveryManager,
                            authManager = authManager,
                            onSetupFinished = {
                                forceWizard = false
                                MonitorService.startIfEnabled(this@MainActivity)
                            },
                        )
                    } else {
                        HomeScreen(
                            settingsRepository = settingsRepository,
                            lockRepository = lockRepository,
                            pinManager = pinManager,
                            recoveryManager = recoveryManager,
                            onReopenSetupWizard = { forceWizard = true },
                        )
                    }
                }
            }
        }
    }
}
