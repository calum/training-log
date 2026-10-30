package com.calum.traininglog

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.SecondaryButton
import com.calum.traininglog.ui.SectionCard
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun prettyDate(iso: String?): String = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault()))
}.getOrDefault("an unknown date")

/** Card shown on the Backup & sync screen for Google Drive: status, sign-in, back up now, restore. */
@Composable
fun DriveBackupCard(vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by DriveBackup.state.collectAsState()
    val found by DriveBackup.found.collectAsState()
    val data by vm.data.collectAsState()
    var confirm by remember { mutableStateOf<Backup?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        DriveBackup.init(ctx)
        if (DriveBackup.state.value is DriveState.Idle) DriveBackup.checkForBackup(ctx)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        DriveBackup.onConsentResult(ctx, r.data, vm.data.value)
    }

    fun connect() {
        message = null
        scope.launch {
            when (val a = DriveBackup.authorize(ctx)) {
                is DriveAuth.Resolution -> launcher.launch(IntentSenderRequest.Builder(a.pendingIntent).build())
                is DriveAuth.Token -> {
                    DriveBackup.backupNow(ctx, vm.data.value)
                    DriveBackup.checkForBackup(ctx)
                }
                DriveAuth.NotConfigured -> DriveBackup.reportNotConfigured()
                is DriveAuth.Error -> DriveBackup.reportError(a.message)
            }
        }
    }

    val status = when (val s = state) {
        DriveState.NotSetUp -> "Not connected."
        DriveState.NeedsSignIn -> "Sign in again to keep backing up."
        is DriveState.Idle -> if (s.lastBackupAt != null) "Last backup: ${prettyDate(s.lastBackupAt)}" else "Connected. No backup yet."
        DriveState.Backing -> "Backing up..."
        is DriveState.Failed -> s.message
    }
    val connected = state is DriveState.Idle || state is DriveState.Backing

    SectionCard(title = "Google Drive backup") {
        Text(status, style = MaterialTheme.typography.bodyLarge, color = AppColors.textPrimary)
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.textPrimary) }

        val f = found
        if (f != null && f.data.records.size > data.records.size) {
            Text(
                "Found a backup from ${prettyDate(f.savedAt)} with ${f.data.records.size} sessions.",
                style = MaterialTheme.typography.bodyMedium, color = AppColors.primary,
            )
            SecondaryButton("Restore", { confirm = f }, Modifier.fillMaxWidth())
        }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!connected) {
                SecondaryButton("Connect Google Drive", { connect() }, Modifier.fillMaxWidth(), accent = AppColors.primary)
            } else {
                SecondaryButton(
                    "Back up now", { DriveBackup.backupNow(ctx, vm.data.value) },
                    Modifier.fillMaxWidth(), enabled = state !is DriveState.Backing, accent = AppColors.primary,
                )
            }
            SecondaryButton(
                "Restore from Drive",
                {
                    message = null; busy = true
                    scope.launch {
                        runCatching { DriveBackup.restore(ctx) }
                            .onSuccess { if (it == null) message = "No backup found on Drive yet." else confirm = it }
                            .onFailure { message = it.message ?: "Couldn't read the backup." }
                        busy = false
                    }
                },
                Modifier.fillMaxWidth(), enabled = !busy,
            )
        }
        Text(
            "Private: only this app can see it; it is not in your normal Drive files.",
            style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
        )
    }

    confirm?.let { b ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Restore from Drive?") },
            text = {
                Text(
                    "Backup from ${prettyDate(b.savedAt)} with ${b.data.records.size} sessions. " +
                        "This replaces everything currently on this phone (${data.records.size} sessions)." +
                        if (b.newerVersion) " It was saved by a newer version of the app, so some details may be missing." else "",
                )
            },
            confirmButton = {
                TextButton({ vm.replaceData(b.data); confirm = null; message = "Restored." }) { Text("Replace my data") }
            },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } },
        )
    }
}
