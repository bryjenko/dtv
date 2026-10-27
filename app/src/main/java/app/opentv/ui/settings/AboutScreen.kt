/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.BuildConfig
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.update.UpdateHub
import app.opentv.update.UpdateChecker
import app.opentv.update.UpdateChecker.CheckResult
import app.opentv.update.UpdateUiState
import kotlinx.coroutines.launch

/** Shows the installed version and checks for updates published on this fork. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var updateLine by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Text(stringResource(R.string.about_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        Text(
            "D-TV ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            enabled = !checking,
            onClick = {
                checking = true
                updateLine = null
                scope.launch {
                    val result = runCatching {
                        val graph = ServiceLocator.get(context)
                        UpdateChecker(graph.httpClient, BuildConfig.VERSION_NAME).checkDetailed()
                    }.getOrElse { CheckResult.Failed }
                    when (result) {
                        is CheckResult.Available -> {
                            updateLine = context.getString(
                                R.string.about_update_available,
                                result.update.versionName,
                            )
                            UpdateHub.state.value = UpdateUiState.Available(result.update)
                        }
                        CheckResult.UpToDate -> updateLine = context.getString(R.string.about_up_to_date)
                        CheckResult.NoRelease -> updateLine = context.getString(R.string.about_no_release)
                        CheckResult.MissingApk -> updateLine = context.getString(R.string.about_missing_apk)
                        CheckResult.Failed -> updateLine = context.getString(R.string.about_check_failed)
                    }
                    checking = false
                }
            },
        ) {
            Text(if (checking) stringResource(R.string.about_checking) else stringResource(R.string.about_check_updates))
        }
        updateLine?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
    }
}
