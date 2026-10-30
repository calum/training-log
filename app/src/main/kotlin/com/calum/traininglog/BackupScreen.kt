package com.calum.traininglog

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.SecondaryButton
import com.calum.traininglog.ui.SectionCard
import com.calum.traininglog.ui.Spacing
import java.text.DateFormat
import java.util.Date

/** Human text for the status line. Pure, so it can be tested. */
internal fun syncStatusText(s: SyncState, fmtTime: (Long) -> String): String = when (s) {
    SyncState.NotConfigured -> "Not set up yet."
    is SyncState.Idle -> if (s.lastSyncAt != null) "Up to date. Last sync ${fmtTime(s.lastSyncAt)}." else "Connected. Nothing synced yet."
    SyncState.Syncing -> "Syncing..."
    is SyncState.Failed -> s.message
    SyncState.NeedsSignIn -> "GitHub rejected the token (expired or revoked). Paste a new one below. Your sessions are queued and will upload then."
}

@Composable
fun BackupScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val state by GithubSync.state.collectAsState()
    val test by GithubSync.test.collectAsState()
    val saved = remember { GithubSync.settings(ctx) }
    var hasToken by remember { mutableStateOf(GithubSync.hasToken(ctx)) }
    var owner by remember { mutableStateOf(saved?.owner ?: "calum") }
    var repo by remember { mutableStateOf(saved?.repo ?: "training-log") }
    var branch by remember { mutableStateOf(saved?.branch ?: "data") }
    var token by remember { mutableStateOf("") } // memory only; never persisted by Compose
    var show by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDisconnect by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { GithubSync.refresh(ctx) }

    val timeFmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val status = syncStatusText(state) { timeFmt.format(Date(it)) }
    val showTokenField = !hasToken || replacing || state == SyncState.NeedsSignIn

    Page {
        Title("Backup & sync")
        SectionCard(title = "What goes where") {
            Text(
                "What goes on GitHub: weights, reps, effort, treadmill sessions.\n" +
                    "What never leaves your phone: how you felt, knee scores, notes, travel time.",
                color = AppColors.textPrimary, fontSize = 15.sp,
            )
            Muted("The GitHub repo is public, so only that short list is ever sent. It uploads on its own after each finished session.")
        }

        SectionCard(title = "GitHub data repo") {
            Muted("Create a public repo from the data repo template, then follow SETUP.md in it to make a fine-grained token (this repo only, Contents: Read and write).")
            OutlinedTextField(
                value = owner, onValueChange = { owner = it; error = null }, label = { Text("GitHub username") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(autoCorrect = false),
            )
            OutlinedTextField(
                value = repo, onValueChange = { repo = it; error = null }, label = { Text("Repo name") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(autoCorrect = false),
            )
            OutlinedTextField(
                value = branch, onValueChange = { branch = it; error = null }, label = { Text("Branch") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(autoCorrect = false),
            )
            if (showTokenField) {
                OutlinedTextField(
                    value = token, onValueChange = { token = it; error = null }, label = { Text("Token") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SecondaryButton(if (show) "Hide" else "Show", onClick = { show = !show }, modifier = Modifier.weight(1f))
                    SecondaryButton("Paste", onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        token = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim() ?: ""
                        error = null
                    }, modifier = Modifier.weight(1f))
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Token saved ••••", color = AppColors.textPrimary, modifier = Modifier.weight(1f))
                    TextButton(onClick = { replacing = true }) { Text("Replace", color = AppColors.primary) }
                }
            }
            error?.let { Text(it, color = AppColors.energy) }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SecondaryButton("Save", onClick = {
                    val msg = GithubSync.save(ctx, owner, repo, branch, token.takeIf { showTokenField })
                    error = msg
                    if (msg == null) {
                        token = ""; show = false; replacing = false; hasToken = true
                    }
                }, modifier = Modifier.weight(1f), accent = AppColors.primary)
                SecondaryButton(
                    "Test connection", onClick = { GithubSync.testConnection(ctx) },
                    enabled = hasToken && state != SyncState.NotConfigured, modifier = Modifier.weight(1f),
                )
            }
            when (val t = test) {
                GithubSync.TestState.Idle -> {}
                GithubSync.TestState.Testing -> Muted("Testing...")
                is GithubSync.TestState.Done ->
                    Text(t.result.message, color = if (t.result.ok) AppColors.primary else AppColors.energy)
            }
        }

        SectionCard(title = "Status") {
            Text(status, color = if (state is SyncState.Failed || state == SyncState.NeedsSignIn) AppColors.energy else AppColors.textPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SecondaryButton(
                    "Sync now", onClick = { GithubSync.syncNow(ctx) },
                    enabled = GithubSync.isConfigured(ctx) && state != SyncState.Syncing && state != SyncState.NotConfigured,
                    modifier = Modifier.weight(1f),
                )
                if (confirmDisconnect) {
                    SecondaryButton("Really disconnect?", onClick = {
                        GithubSync.disconnect(ctx)
                        hasToken = false; token = ""; replacing = false; confirmDisconnect = false
                    }, modifier = Modifier.weight(1f), accent = AppColors.energy)
                } else {
                    SecondaryButton(
                        "Disconnect", onClick = { confirmDisconnect = true },
                        enabled = hasToken || state != SyncState.NotConfigured, modifier = Modifier.weight(1f),
                    )
                }
            }
            if (confirmDisconnect) Muted("This deletes the stored token from this phone. Data already on GitHub stays there; revoke the token on GitHub too.")
        }

        DriveBackupCard(vm)

        SecondaryButton("Back", onClick = { vm.home() }, modifier = Modifier.fillMaxWidth())
    }
}
