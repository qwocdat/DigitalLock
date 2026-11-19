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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.digitallock.cache.LastKnownGoodCacheManager
import com.digitallock.data.LockRepository
import com.digitallock.data.SettingsRepository
import com.digitallock.lock.SessionManager
import com.digitallock.remote.ResourceSyncWorker
import com.digitallock.remote.ServerSyncRepository
import com.digitallock.security.AuthenticationManager
import com.digitallock.security.CredentialStore
import com.digitallock.security.IntegrityCoordinator
import com.digitallock.security.PinManager
import com.digitallock.security.RecoveryManager
import com.digitallock.security.ServerLicenseManager
import com.digitallock.service.MonitorService
import com.digitallock.ui.components.DigitalLockExpressiveTheme
import com.digitallock.ui.home.HomeScreen
import com.digitallock.ui.launch.AppLaunchScreen
import com.digitallock.ui.setup.SetupWizardScreen

/**
 * Main Material 3 Expressive entry point for DigitalLock.
 *
 * - Enforces [AppLaunchScreen] on launch to verify License & load `inject.so` from the PHP Backend
 *   (or encrypted Last Known Good cache when offline after initial activation).
 * - Restores the original native Material 3 Expressive [SetupWizardScreen] & [HomeScreen] UI
 *   once `inject.so` is verified and loaded.
 */
class MainActivity : ComponentActivity() {

    private lateinit var credentialStore: CredentialStore
    private lateinit var pinManager: PinManager
    private lateinit var recoveryManager: RecoveryManager
    private lateinit var authManager: AuthenticationManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var lockRepository: LockRepository
    private lateinit var cacheManager: LastKnownGoodCacheManager
    private lateinit var licenseManager: ServerLicenseManager
    private lateinit var syncRepository: ServerSyncRepository
    private lateinit var integrityCoordinator: IntegrityCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        SessionManager.init(this)
        credentialStore = CredentialStore(this)
        pinManager = PinManager(credentialStore)
        recoveryManager = RecoveryManager(credentialStore)
        authManager = AuthenticationManager(this, credentialStore, pinManager, recoveryManager)
        settingsRepository = SettingsRepository(this)
        lockRepository = LockRepository(this)
        cacheManager = LastKnownGoodCacheManager.getInstance(this)
        licenseManager = ServerLicenseManager.getInstance(this)
        syncRepository = ServerSyncRepository(this, cacheManager, licenseManager)
        integrityCoordinator = IntegrityCoordinator(this)

        val integrityReport = integrityCoordinator.runLocalIntegrityCheck()
        if (!integrityReport.isClean) {
            cacheManager.appendSafeLog("Integrity warnings: ${integrityReport.warnings.size}")
        }

        if (cacheManager.isFirstInitializationCompleted()) {
            MonitorService.startIfEnabled(this)
            ResourceSyncWorker.schedulePeriodicSync(this)
        }

        setContent {
            val settings by settingsRepository.settingsFlow.collectAsState()
            var launchCompleted by rememberSaveable { mutableStateOf(false) }
            var forceWizard by remember { mutableStateOf(false) }

            DigitalLockExpressiveTheme(
                dynamicColor = settings.dynamicColorEnabled,
                darkThemeOverride = settings.darkThemePreference,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    if (!launchCompleted) {
                        AppLaunchScreen(
                            syncRepository = syncRepository,
                            onReadyToProceed = {
                                val injectPayload = cacheManager.loadVerifiedInjectSoPayload()
                                if (injectPayload != null && !settings.setupCompleted) {
                                    settingsRepository.setOpticalTuning(
                                        injectPayload.defaultRefraction,
                                        injectPayload.defaultAberration,
                                    )
                                }
                                launchCompleted = true
                                ResourceSyncWorker.schedulePeriodicSync(this@MainActivity)
                                MonitorService.startIfEnabled(this@MainActivity)
                            },
                        )
                    } else if (!settings.setupCompleted || !pinManager.isPrimaryPinConfigured() || forceWizard) {
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
