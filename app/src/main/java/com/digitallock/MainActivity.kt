package com.digitallock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.digitallock.cache.LastKnownGoodCacheManager
import com.digitallock.data.LockRepository
import com.digitallock.data.SettingsRepository
import com.digitallock.lock.SessionManager
import com.digitallock.remote.ResourceSyncWorker
import com.digitallock.remote.ServerSyncRepository
import com.digitallock.runtime.DigitalLockNativeBridge
import com.digitallock.runtime.SecureWebViewRuntime
import com.digitallock.security.CredentialStore
import com.digitallock.security.IntegrityCoordinator
import com.digitallock.security.PinManager
import com.digitallock.security.RecoveryManager
import com.digitallock.security.ServerLicenseManager
import com.digitallock.service.MonitorService
import com.digitallock.ui.launch.AppLaunchScreen

/**
 * Minimal Android Bootstrap Activity for DigitalLock v3.0 Backend-Hosted Architecture.
 *
 * - Runs [AppLaunchScreen] (Server License Gate + Signed UI Bundle Sync + Real-Time 0–100% progress).
 * - Renders the verified, AES-GCM decrypted Backend-Hosted HTML/CSS/JS Frontend inside [SecureWebViewRuntime].
 * - APK does NOT embed a duplicate offline substitute UI; native runtime strictly provides Android adapters,
 *   hardware-backed PIN storage, LockActivity/AccessibilityMonitor, C++ Liquid Glass, and Rust verification.
 */
class MainActivity : ComponentActivity() {

    private lateinit var credentialStore: CredentialStore
    private lateinit var pinManager: PinManager
    private lateinit var recoveryManager: RecoveryManager
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
            var launchCompleted by rememberSaveable { mutableStateOf(false) }
            var syncGeneration by rememberSaveable { mutableIntStateOf(0) }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF080B12),
            ) {
                if (!launchCompleted) {
                    AppLaunchScreen(
                        syncRepository = syncRepository,
                        onReadyToProceed = {
                            launchCompleted = true
                            ResourceSyncWorker.schedulePeriodicSync(this@MainActivity)
                            MonitorService.startIfEnabled(this@MainActivity)
                        },
                    )
                } else {
                    SecureWebViewRuntime(
                        cacheManager = cacheManager,
                        bridgeFactory = { originVerifier ->
                            DigitalLockNativeBridge(
                                context = this@MainActivity,
                                settingsRepository = settingsRepository,
                                lockRepository = lockRepository,
                                pinManager = pinManager,
                                recoveryManager = recoveryManager,
                                cacheManager = cacheManager,
                                licenseManager = licenseManager,
                                integrityCoordinator = integrityCoordinator,
                                originVerifier = originVerifier,
                                onSyncRequested = {
                                    runOnUiThread {
                                        syncGeneration++
                                        launchCompleted = false
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}
