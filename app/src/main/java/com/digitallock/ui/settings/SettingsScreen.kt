package com.digitallock.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.digitallock.data.SettingsRepository

/**
 * Material 3 Expressive Preferences Section for configuring Dynamic Color, FLAG_SECURE
 * screenshot protection on LockActivity, and Biometric unlock.
 */
@Composable
fun SettingsPreferenceSection(
    settingsRepository: SettingsRepository,
    biometricHardwareAvailable: Boolean,
    modifier: Modifier = Modifier,
) {
    val settings by settingsRepository.settingsFlow.collectAsState()

    Card(
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Security & Appearance Settings", style = MaterialTheme.typography.titleLarge)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Material 3 Dynamic Color", fontWeight = FontWeight.Medium)
                    Text("Adapts Main App theme to system wallpaper palette (Lock Screen remains pure Liquid Glass).")
                }
                Switch(
                    checked = settings.dynamicColorEnabled,
                    onCheckedChange = { settingsRepository.setDynamicColorEnabled(it) },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Block External Screenshots on LockActivity (FLAG_SECURE)", fontWeight = FontWeight.Medium)
                    Text("Prevents external screen capture of PIN entry while preserving internal Compose LayerBackdrop refraction.")
                }
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
                Column(modifier = Modifier.weight(1f)) {
                    Text("Class 3 Biometric Authentication", fontWeight = FontWeight.Medium)
                    Text("Allow unlocking LockActivity via BiometricPrompt.")
                }
                Switch(
                    checked = settings.biometricEnabled && biometricHardwareAvailable,
                    enabled = biometricHardwareAvailable,
                    onCheckedChange = { settingsRepository.setBiometricEnabled(it) },
                )
            }
        }
    }
}
