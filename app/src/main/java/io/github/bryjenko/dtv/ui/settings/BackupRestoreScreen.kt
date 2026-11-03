/*
 * This file is part of D-TV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package io.github.bryjenko.dtv.ui.settings

import android.app.Activity
import android.content.Intent
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.bryjenko.dtv.backup.BackupPayload
import io.github.bryjenko.dtv.backup.ConfigBackup
import io.github.bryjenko.dtv.backup.DriveBackupStore
import io.github.bryjenko.dtv.core.ServiceLocator
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class BackupAction { CREATE, RESTORE }

@Composable
fun BackupRestoreScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val graph = remember { ServiceLocator.get(context) }
    val scope = rememberCoroutineScope()
    val drive = remember { DriveBackupStore(graph.httpClient) }
    val authClient = remember(activity) { activity?.let { Identity.getAuthorizationClient(it) } }
    var action by remember { mutableStateOf<BackupAction?>(null) }
    var token by remember { mutableStateOf<String?>(null) }
    var stage by remember { mutableStateOf("home") }
    var code by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var encryptedRestore by remember { mutableStateOf<ByteArray?>(null) }
    var restorePayload by remember { mutableStateOf<BackupPayload?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun continueWithToken(accessToken: String) {
        token = accessToken
        val chosen = action ?: return
        if (chosen == BackupAction.CREATE) {
            stage = "create-code"
        } else {
            busy = true
            scope.launch {
                try {
                    encryptedRestore = withContext(Dispatchers.IO) { drive.download(accessToken) }
                    stage = "restore-code"
                } catch (e: Exception) {
                    message = e.message ?: "Could not find the backup."
                    stage = "home"
                } finally { busy = false }
            }
        }
    }

    val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        try {
            val authResult: AuthorizationResult = authClient!!.getAuthorizationResultFromIntent(result.data ?: Intent())
            authResult.accessToken?.let(::continueWithToken)
                ?: run {
                    message = if (result.resultCode == Activity.RESULT_OK) {
                        "Google did not provide Drive access."
                    } else {
                        "Google did not grant Drive access (result ${result.resultCode}). If you selected an account, D-TV's release signing certificate may need to be registered for Drive access in Google Cloud."
                    }
                }
        } catch (e: ApiException) {
            message = when (e.statusCode) {
                CommonStatusCodes.CANCELED -> "Google account access was closed before Drive permission was granted."
                CommonStatusCodes.DEVELOPER_ERROR -> "Google Drive access is not configured for this D-TV release. Register its app signing certificate in Google Cloud."
                else -> "Google account access failed (${e.statusCode}): ${e.statusMessage ?: e.message ?: "unknown Google error"}"
            }
        } catch (e: Exception) {
            message = "Could not finish Google account access: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun authorize(requestedAction: BackupAction) {
        action = requestedAction
        message = null
        val client = authClient
        if (client == null) {
            message = "Google Play services are not available on this device."
            return
        }
        busy = true
        client.authorize(
            AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.appdata")))
                .build(),
        ).addOnSuccessListener { result ->
            busy = false
            if (result.hasResolution()) {
                val pending = result.pendingIntent
                if (pending == null) message = "Google could not open the account permission screen."
                else authLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
            } else {
                result.accessToken?.let(::continueWithToken)
                    ?: run { message = "Google did not provide Drive access." }
            }
        }.addOnFailureListener { e ->
            busy = false
            message = e.message ?: "Could not connect to Google Drive."
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Backup & restore", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            HubTextButton("Back", onBack)
        }
        when (stage) {
            "home" -> {
                Text("Save your providers and app settings to the private D-TV backup area in your Google Drive. Use the same Google account and six digit code on the other TV.")
                Text("The backup is encrypted before it leaves this device. Keep the code safe; it cannot be reset or recovered.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HubTextButton("Back up to Google Drive", { authorize(BackupAction.CREATE) })
                    HubTextButton("Restore from Google Drive", { authorize(BackupAction.RESTORE) })
                }
            }
            "create-code" -> {
                Text("Choose a six digit backup code", style = MaterialTheme.typography.titleLarge)
                Text("Enter this same code on the TV where you restore the backup.")
                BackupCodeField("6 digit code", code) { code = it }
                BackupCodeField("Confirm code", confirmation) { confirmation = it }
                HubTextButton("Encrypt and back up", {
                    if (code.length != 6 || confirmation.length != 6) message = "Enter the code twice."
                    else if (code != confirmation) message = "The codes do not match."
                    else {
                        busy = true
                        scope.launch {
                            try {
                                val plain = withContext(Dispatchers.IO) { ConfigBackup.create(graph.settings, graph.database.sources()) }
                                val ciphertext = withContext(Dispatchers.Default) { ConfigBackup.encrypt(plain, code) }
                                withContext(Dispatchers.IO) { drive.upload(token!!, ciphertext) }
                                stage = "home"
                                code = ""; confirmation = ""
                                message = "Your encrypted backup is saved in Google Drive."
                            } catch (e: Exception) {
                                message = e.message ?: "Could not save the backup."
                            } finally { busy = false }
                        }
                    }
                })
            }
            "restore-code" -> {
                Text("Enter your six digit backup code", style = MaterialTheme.typography.titleLarge)
                BackupCodeField("6 digit code", code) { code = it }
                HubTextButton("Unlock backup", {
                    val data = encryptedRestore
                    if (code.length != 6 || data == null) message = "Enter all six digits."
                    else {
                        busy = true
                        scope.launch {
                            try {
                                val plain = withContext(Dispatchers.Default) { ConfigBackup.decrypt(data, code) }
                                restorePayload = ConfigBackup.inspect(plain)
                                stage = "confirm-restore"
                                code = ""
                            } catch (_: Exception) {
                                message = "That code did not unlock the backup. Check the digits and try again."
                                code = ""
                            } finally { busy = false }
                        }
                    }
                })
            }
            "confirm-restore" -> {
                val payload = restorePayload
                Text("Restore this backup?", style = MaterialTheme.typography.titleLarge)
                Text("It contains ${payload?.sources?.size ?: 0} provider entries and your D-TV settings. This will replace the current provider list and preferences. Channel lists will refresh from those providers after restore.")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HubTextButton("Restore now", {
                        if (payload == null) return@HubTextButton
                        busy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { ConfigBackup.restore(payload, graph.settings, graph.database.sources()) }
                                message = "Restore complete. D-TV will restart to apply your settings."
                                stage = "home"
                                (activity as? MainActivityHost)?.restartAfterBackupRestore()
                            } catch (e: Exception) {
                                message = e.message ?: "Could not restore the backup."
                            } finally { busy = false }
                        }
                    })
                    HubTextButton("Cancel", { stage = "home"; restorePayload = null })
                }
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator()
            Spacer(Modifier.width(12.dp))
            Text("Working…")
        }
    }
}

/** Keeps this composable independent of the concrete activity implementation. */
interface MainActivityHost { fun restartAfterBackupRestore() }

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun BackupCodeField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(it.filter(Char::isDigit).take(6)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.width(280.dp),
    )
}
