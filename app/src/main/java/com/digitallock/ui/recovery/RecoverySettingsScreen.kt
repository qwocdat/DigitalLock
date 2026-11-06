package com.digitallock.ui.recovery

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.digitallock.security.PinManager
import com.digitallock.security.RecoveryManager

/**
 * Dedicated Material 3 Expressive screen for rotating Secondary PIN, regenerating a one-time
 * Recovery Code, and updating the normalized Security Question.
 */
@Composable
fun RecoverySettingsSection(
    pinManager: PinManager,
    recoveryManager: RecoveryManager,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var secondaryPin by remember { mutableStateOf("") }
    var secondaryConfirm by remember { mutableStateOf("") }
    var generatedCode by remember { mutableStateOf<String?>(null) }
    var question by remember { mutableStateOf(recoveryManager.getSecurityQuestionPrompt().orEmpty()) }
    var answer by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf<String?>(null) }

    Card(
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Recovery Management", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = secondaryPin,
                onValueChange = { if (it.length <= 12) secondaryPin = it },
                label = { Text("New Secondary PIN (Must Differ from Primary)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = secondaryConfirm,
                onValueChange = { if (it.length <= 12) secondaryConfirm = it },
                label = { Text("Confirm Secondary PIN") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = {
                    when (val res = pinManager.setupSecondaryPin(secondaryPin, secondaryConfirm)) {
                        is PinManager.PinSetupResult.Success -> {
                            secondaryPin = ""
                            secondaryConfirm = ""
                            statusText = "Secondary PIN updated."
                        }
                        is PinManager.PinSetupResult.ValidationError -> statusText = res.message
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Update Secondary PIN")
            }

            Button(
                onClick = {
                    val code = recoveryManager.generateAndStoreOneTimeRecoveryCode()
                    generatedCode = code
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(ClipData.newPlainText("DigitalLock Recovery Code", code))
                    statusText = "Generated and copied new one-time Recovery Code."
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Generate New One-Time Recovery Code")
            }

            generatedCode?.let { oneTime ->
                Text(
                    text = "One-Time Code (Save Now): $oneTime",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            Text(
                text = RecoveryManager.SECURITY_QUESTION_ENTROPY_WARNING,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                label = { Text("Security Question") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = answer,
                onValueChange = { answer = it },
                label = { Text("Security Answer") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = {
                    if (recoveryManager.setupSecurityQuestion(question, answer)) {
                        answer = ""
                        statusText = "Security Question saved with normalized PBKDF2 hash."
                    } else {
                        statusText = "Please enter a valid question and answer."
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save Security Question")
            }

            statusText?.let {
                Text(text = it, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}
