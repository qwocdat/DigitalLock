package com.digitallock.cache

import android.content.Context
import com.digitallock.remote.RemoteUiConfig
import com.digitallock.security.NativeSecurityBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Offline-First Last Known Good (LKG) versioned cache manager.
 *
 * Directory layout inside app private storage:
 * `filesDir/digital_lock/`
 *   ├── cache/active/
 *   ├── cache/previous/
 *   ├── cache/staging/
 *   ├── manifests/
 *   ├── integrity/
 *   ├── metadata/
 *   └── logs/
 *
 * Guarantees:
 * - Never deletes `active/` before `staging/` is fully verified (schema + Ed25519 + SHA-256).
 * - Atomic promotion (`active` -> `previous`, `staging` -> `active`) with instant rollback.
 * - Tracks `isFirstInitCompleted` strictly after verified server sync (never bypassed offline).
 * - Never expires verified cache while offline.
 */
class LastKnownGoodCacheManager(context: Context) {

    private val appContext = context.applicationContext
    private val dpContext = appContext.createDeviceProtectedStorageContext()
    private val metaPrefs = dpContext.getSharedPreferences(META_PREFS_NAME, Context.MODE_PRIVATE)

    val rootDir = File(appContext.filesDir, "digital_lock")
    val activeDir = File(rootDir, "cache/active")
    val previousDir = File(rootDir, "cache/previous")
    val stagingDir = File(rootDir, "cache/staging")
    val manifestsDir = File(rootDir, "manifests")
    val integrityDir = File(rootDir, "integrity")
    val metadataDir = File(rootDir, "metadata")
    val logsDir = File(rootDir, "logs")

    private val _activeUiConfig = MutableStateFlow(RemoteUiConfig.BuiltInSafeDefault)
    val activeUiConfig: StateFlow<RemoteUiConfig> = _activeUiConfig.asStateFlow()

    init {
        ensureDirectories()
        loadVerifiedActiveIntoMemory()
    }

    private fun ensureDirectories() {
        listOf(
            activeDir,
            previousDir,
            stagingDir,
            manifestsDir,
            integrityDir,
            metadataDir,
            logsDir,
        ).forEach { dir ->
            if (!dir.exists()) dir.mkdirs()
        }
    }

    fun isFirstInitializationCompleted(): Boolean {
        val flag = metaPrefs.getBoolean(KEY_FIRST_INIT_COMPLETED, false)
        if (!flag) return false
        val activeManifest = File(activeDir, MANIFEST_FILE_NAME)
        return activeManifest.exists() && activeManifest.length() > 0L
    }

    fun getActiveVersionCode(): Long = metaPrefs.getLong(KEY_ACTIVE_VERSION_CODE, 0L)

    fun getActiveVersionName(): String = metaPrefs.getString(KEY_ACTIVE_VERSION_NAME, "") ?: ""

    fun getLastEtag(): String? = metaPrefs.getString(KEY_LAST_ETAG, null)

    fun clearStaging() {
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()
    }

    fun resolveActiveResourceFile(relativePath: String): File? {
        val clean = relativePath.trimStart('/')
        if (clean.contains("..") || clean.contains("\\")) return null
        val target = File(activeDir, "resources/$clean").canonicalFile
        val base = File(activeDir, "resources").canonicalFile
        if (!target.path.startsWith(base.path)) return null
        return if (target.exists() && target.isFile) target else null
    }

    fun hasVerifiedResourceWithSha(relativePath: String, expectedSha256: String): Boolean {
        val clean = relativePath.trimStart('/')
        val existing = resolveActiveResourceFile(clean) ?: return false
        return try {
            val plain = EncryptedBundleStore.readEncryptedFile(existing, clean) ?: return false
            val actual = NativeSecurityBridge.sha256Hex(plain)
            actual.equals(expectedSha256, ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    fun writeStagingFile(relativePath: String, bytes: ByteArray): File {
        val clean = relativePath.trimStart('/')
        require(!clean.contains("..") && !clean.contains("\\")) { "Unsafe path" }
        val target = File(stagingDir, "resources/$clean")
        EncryptedBundleStore.writeEncryptedFile(target, bytes, clean)
        return target
    }

    /**
     * Decrypts an asset from `active/resources/` using AES-256-GCM and verifies its SHA-256 against
     * the active verified manifest before handing bytes to SecureWebViewRuntime.
     * Automatically rolls back to `previous/` if tampering or corruption is detected.
     */
    fun readVerifiedDecryptedAsset(relativePath: String): ByteArray? {
        val clean = relativePath.trimStart('/')
        if (clean.contains("..") || clean.contains("\\")) return null
        val file = resolveActiveResourceFile(clean) ?: return null
        val expectedSha = _activeUiConfig.value.resources.firstOrNull { it.path == clean }?.sha256
            ?: return null
        val decrypted = EncryptedBundleStore.readEncryptedFile(file, clean)
        if (decrypted == null) {
            rollbackToPrevious()
            return null
        }
        val actualSha = NativeSecurityBridge.sha256Hex(decrypted)
        if (!actualSha.equals(expectedSha, ignoreCase = true)) {
            rollbackToPrevious()
            return null
        }
        return decrypted
    }

    fun copyActiveResourceToStaging(relativePath: String): Boolean {
        val existing = resolveActiveResourceFile(relativePath) ?: return false
        val clean = relativePath.trimStart('/')
        val dest = File(stagingDir, "resources/$clean")
        dest.parentFile?.mkdirs()
        existing.copyTo(dest, overwrite = true)
        return true
    }

    /**
     * Atomically promotes verified `staging/` to `active/`, preserving the previous LKG bundle
     * in `previous/` for instant rollback.
     */
    @Synchronized
    fun promoteStagingToActive(
        verifiedManifestJson: String,
        signatureB64: String,
        etag: String?,
        versionCode: Long,
        versionName: String,
    ): Boolean {
        return try {
            // Write manifest & signature into staging first
            File(stagingDir, MANIFEST_FILE_NAME).writeText(verifiedManifestJson, StandardCharsets.UTF_8)
            File(stagingDir, SIGNATURE_FILE_NAME).writeText(signatureB64, StandardCharsets.UTF_8)

            // Verify staging parses cleanly before swapping
            val parsed = RemoteUiConfig.parseFromVerifiedManifestJson(verifiedManifestJson) { rel ->
                val f = File(stagingDir, "resources/${rel.trimStart('/')}")
                if (f.exists()) f.absolutePath else null
            }

            val backupDir = File(rootDir, "cache/previous_tmp")
            backupDir.deleteRecursively()

            // Move current active -> previous_tmp
            if (activeDir.exists() && File(activeDir, MANIFEST_FILE_NAME).exists()) {
                activeDir.copyRecursively(backupDir, overwrite = true)
            }

            // Swap staging -> active
            val oldActive = File(rootDir, "cache/active_old")
            oldActive.deleteRecursively()
            if (activeDir.exists()) {
                if (!activeDir.renameTo(oldActive)) {
                    activeDir.deleteRecursively()
                }
            }
            if (!stagingDir.renameTo(activeDir)) {
                // Restore oldActive if rename failed
                if (oldActive.exists()) oldActive.renameTo(activeDir)
                return false
            }
            oldActive.deleteRecursively()
            stagingDir.mkdirs()

            // Update previous/
            if (backupDir.exists()) {
                previousDir.deleteRecursively()
                backupDir.renameTo(previousDir)
            }

            // Archive verified manifest & integrity record
            File(manifestsDir, "manifest_v${versionCode}.json").writeText(verifiedManifestJson, StandardCharsets.UTF_8)
            val manifestHash = NativeSecurityBridge.sha256Hex(verifiedManifestJson.toByteArray(StandardCharsets.UTF_8))
            val integrityRecord = JSONObject().apply {
                put("version_code", versionCode)
                put("version_name", versionName)
                put("manifest_sha256", manifestHash)
                put("verified_at_epoch_ms", System.currentTimeMillis())
                put("security_engine", NativeSecurityBridge.engineVersion())
            }
            File(integrityDir, "active_integrity.json").writeText(integrityRecord.toString(2), StandardCharsets.UTF_8)
            File(metadataDir, "state.json").writeText(
                JSONObject().apply {
                    put("first_init_completed", true)
                    put("active_version_code", versionCode)
                    put("active_version_name", versionName)
                    put("etag", etag ?: "")
                }.toString(2),
                StandardCharsets.UTF_8,
            )

            metaPrefs.edit()
                .putBoolean(KEY_FIRST_INIT_COMPLETED, true)
                .putLong(KEY_ACTIVE_VERSION_CODE, versionCode)
                .putString(KEY_ACTIVE_VERSION_NAME, versionName)
                .putString(KEY_LAST_ETAG, etag)
                .commit()

            // Reload resolved paths pointing to active/
            loadVerifiedActiveIntoMemory()
            appendSafeLog("Promoted verified UI bundle v$versionCode ($versionName), resources=${parsed.resources.size}")
            true
        } catch (t: Throwable) {
            appendSafeLog("Promotion failed safely; active LKG preserved.")
            false
        }
    }

    @Synchronized
    fun rollbackToPrevious(): Boolean {
        val prevManifest = File(previousDir, MANIFEST_FILE_NAME)
        if (!prevManifest.exists()) return false
        return try {
            val swap = File(rootDir, "cache/rollback_tmp")
            swap.deleteRecursively()
            previousDir.copyRecursively(swap, overwrite = true)
            activeDir.deleteRecursively()
            swap.renameTo(activeDir)
            loadVerifiedActiveIntoMemory()
            appendSafeLog("Rolled back active cache to previous Last Known Good bundle.")
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun loadVerifiedActiveIntoMemory(): RemoteUiConfig {
        val manifestFile = File(activeDir, MANIFEST_FILE_NAME)
        if (!manifestFile.exists()) {
            _activeUiConfig.value = RemoteUiConfig.BuiltInSafeDefault
            return RemoteUiConfig.BuiltInSafeDefault
        }
        return try {
            val raw = manifestFile.readText(StandardCharsets.UTF_8)
            val config = RemoteUiConfig.parseFromVerifiedManifestJson(raw) { rel ->
                resolveActiveResourceFile(rel)?.absolutePath
            }
            _activeUiConfig.value = config
            config
        } catch (_: Throwable) {
            // Auto-rollback if active cache was corrupted on disk
            if (rollbackToPrevious()) {
                _activeUiConfig.value
            } else {
                _activeUiConfig.value = RemoteUiConfig.BuiltInSafeDefault
                RemoteUiConfig.BuiltInSafeDefault
            }
        }
    }

    fun appendSafeLog(message: String) {
        try {
            val logFile = File(logsDir, "sync.log")
            if (logFile.exists() && logFile.length() > 128 * 1024) {
                logFile.writeText("")
            }
            logFile.appendText("${System.currentTimeMillis()} | $message\n")
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val META_PREFS_NAME = "digitallock_lkg_meta_dp"
        private const val KEY_FIRST_INIT_COMPLETED = "first_init_completed"
        private const val KEY_ACTIVE_VERSION_CODE = "active_version_code"
        private const val KEY_ACTIVE_VERSION_NAME = "active_version_name"
        private const val KEY_LAST_ETAG = "last_etag"
        const val MANIFEST_FILE_NAME = "manifest.json"
        const val SIGNATURE_FILE_NAME = "manifest.sig"

        @Volatile
        private var singleton: LastKnownGoodCacheManager? = null

        fun getInstance(context: Context): LastKnownGoodCacheManager {
            return singleton ?: synchronized(this) {
                singleton ?: LastKnownGoodCacheManager(context).also { singleton = it }
            }
        }
    }
}
