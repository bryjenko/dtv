/*
 * This file is part of D-TV.
 * Copyright (C) 2026 Bryan Jenkinson
 * Licensed under the GNU General Public License v3.0 or later.
 */
package io.github.bryjenko.dtv.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.bryjenko.dtv.backup.BackupTransferServer
import io.github.bryjenko.dtv.core.ServiceLocator
import io.github.bryjenko.dtv.data.work.SyncWorker
import io.github.bryjenko.dtv.pairing.QrCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

@Composable
fun BackupRestoreScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val graph = remember { ServiceLocator.get(context) }
    val scope = remember { kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val server = remember { BackupTransferServer(scope, graph.settings, graph.database.sources(), graph.database.channels()) }
    val state by server.state.collectAsState()
    DisposableEffect(server) { server.start(); onDispose { server.stop(); scope.cancel() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Backup & restore", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            HubTextButton("Back", onBack)
        }
        Text("Scan this QR code with your phone, then choose Backup or Restore. The six digit code is created on your phone.")
        Text("The transfer is encrypted and sent directly between devices on your local network.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (val current = state) {
            is BackupTransferServer.State.Listening -> {
                val bitmap = remember(current.url) { QrCodes.render(current.url, 420) }
                if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = "Scan to back up or restore D-TV", modifier = Modifier.size(240.dp).align(Alignment.CenterHorizontally))
                Text(current.url, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                Text("Keep this screen open while transferring. Both devices must be on the same Wi-Fi network.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is BackupTransferServer.State.Complete -> {
                Text(current.message, color = MaterialTheme.colorScheme.primary)
                HubTextButton("Done", {
                    SyncWorker.refreshNow(context)
                    (activity as? MainActivityHost)?.restartAfterBackupRestore()
                    onBack()
                })
            }
            is BackupTransferServer.State.Failed -> {
                Text(current.message, color = MaterialTheme.colorScheme.error)
                HubTextButton("Try again", { server.start() })
            }
            BackupTransferServer.State.Idle -> {
                CircularProgressIndicator()
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

interface MainActivityHost { fun restartAfterBackupRestore() }

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
