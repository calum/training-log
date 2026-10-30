package com.calum.traininglog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import com.calum.traininglog.core.PublicImport
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import com.calum.traininglog.ui.AppShapes
import com.calum.traininglog.ui.AppType
import com.calum.traininglog.ui.Pill
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
    SyncState.NeedsSignIn -> "GitHub sign-in expired or was revoked. Sign in again below (or paste a new token). Your sessions are queued and will upload then."
}

@Composable
fun BackupScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val state by GithubSync.state.collectAsState()
    val test by GithubSync.test.collectAsState()
    val saved = remember { GithubSync.settings(ctx) }
    val device by GithubSync.device.collectAsState()
    val restore by GithubSync.restore.collectAsState()
    val appData by vm.data.collectAsState()
    var hasToken by remember { mutableStateOf(GithubSync.hasToken(ctx)) }
    var signedIn by remember { mutableStateOf(GithubSync.isDeviceSignedIn(ctx)) }
    var clientId by remember { mutableStateOf(GithubSync.clientId(ctx) ?: "") }
    var usePat by remember { mutableStateOf(GithubSync.hasToken(ctx) && !GithubSync.isDeviceSignedIn(ctx) && GithubSync.clientId(ctx) == null) }
    var owner by remember { mutableStateOf(saved?.owner ?: "calum") }
    var repo by remember { mutableStateOf(saved?.repo ?: "training-log") }
    var branch by remember { mutableStateOf(saved?.branch ?: "data") }
    var token by remember { mutableStateOf("") } // memory only; never persisted by Compose
    var show by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDisconnect by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { GithubSync.refresh(ctx) }
    // Sign-in finishes in the background: pick up the stored result.
    LaunchedEffect(state, device) {
        hasToken = GithubSync.hasToken(ctx)
        signedIn = GithubSync.isDeviceSignedIn(ctx)
    }

    val timeFmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val status = syncStatusText(state) { timeFmt.format(Date(it)) }
    val showTokenField = usePat && (!hasToken || signedIn || replacing || state == SyncState.NeedsSignIn)

    Page {
        Title("Backup & sync")
        SectionCard(title = "What goes where") {
            Row(verticalAlignment = androidx.compose.ui.Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Pill("On GitHub", color = AppColors.primary, filled = true)
                Text("Weights, reps, effort, treadmill sessions.", color = AppColors.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Pill("Phone only", color = AppColors.reward, filled = true)
                Text("How you felt, knee scores, notes, travel time.", color = AppColors.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
            Muted("The GitHub repo is public, so only the first list is ever sent. It uploads on its own after each finished session.")
        }

        SectionCard(title = "Status") {
            Text(status, style = MaterialTheme.typography.bodyLarge, color = if (state is SyncState.Failed || state == SyncState.NeedsSignIn) AppColors.energy else AppColors.textPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SecondaryButton(
                    "Sync now", onClick = { GithubSync.syncNow(ctx) },
                    enabled = GithubSync.isConfigured(ctx) && state != SyncState.Syncing && state != SyncState.NotConfigured,
                    modifier = Modifier.weight(1f),
                )
                if (confirmDisconnect) {
                    SecondaryButton("Really disconnect?", onClick = {
                        GithubSync.disconnect(ctx)
                        hasToken = false; signedIn = false; token = ""; replacing = false; confirmDisconnect = false
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

        SectionCard(title = "GitHub data repo") {
            Muted("Create a public repo from the data repo template (SETUP.md), then sign in with your own GitHub App (docs/github-app-setup.md). The sign-in renews itself, so there is no token to paste again.")
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
            if (!usePat) {
                val ok = signedIn && state != SyncState.NeedsSignIn
                if (ok) {
                    Text("Signed in with GitHub", color = AppColors.primary)
                } else {
                    OutlinedTextField(
                        value = clientId, onValueChange = { clientId = it; error = null }, label = { Text("GitHub App Client ID") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(autoCorrect = false),
                    )
                    Muted("Each person makes their own GitHub App (about five minutes; see docs/github-app-setup.md). Its Client ID is public and safe to store here. Never enter a client secret.")
                }
                when (val d = device) {
                    GithubSync.DeviceState.Idle -> SecondaryButton(
                        if (ok) "Sign in again" else "Sign in with GitHub",
                        onClick = { error = GithubSync.startDeviceSignIn(ctx, clientId.ifBlank { GithubSync.clientId(ctx) ?: "" }, owner, repo, branch) },
                        modifier = Modifier.fillMaxWidth(), accent = if (ok) AppColors.textPrimary else AppColors.primary,
                    )
                    GithubSync.DeviceState.Starting -> Muted("Asking GitHub for a code...")
                    is GithubSync.DeviceState.Waiting -> {
                        Column(
                            Modifier.fillMaxWidth().clip(AppShapes.large).background(AppColors.reward.copy(alpha = 0.14f))
                                .border(1.5.dp, AppColors.reward.copy(alpha = 0.6f), AppShapes.large).padding(Spacing.lg),
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            Text("YOUR CODE", style = AppType.eyebrow, color = AppColors.reward)
                            Text(
                                d.userCode, color = AppColors.textPrimary, style = AppType.numberL,
                                fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
                            )
                            Muted("1. Open GitHub and type this code. 2. Approve the app. This screen finishes by itself.")
                        }
                        SecondaryButton("Copy code", onClick = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("GitHub code", d.userCode))
                        }, modifier = Modifier.fillMaxWidth())
                        SecondaryButton("Open github.com/login/device", onClick = {
                            runCatching {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(d.verificationUri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }, modifier = Modifier.fillMaxWidth(), accent = AppColors.primary)
                        SecondaryButton("Cancel", onClick = { GithubSync.cancelDeviceSignIn() }, modifier = Modifier.fillMaxWidth())
                    }
                    is GithubSync.DeviceState.Failed -> {
                        Text(d.message, color = AppColors.energy)
                        SecondaryButton("Try again", onClick = {
                            error = GithubSync.startDeviceSignIn(ctx, clientId.ifBlank { GithubSync.clientId(ctx) ?: "" }, owner, repo, branch)
                        }, modifier = Modifier.fillMaxWidth(), accent = AppColors.primary)
                    }
                }
                TextButton(onClick = { usePat = true; GithubSync.cancelDeviceSignIn() }) {
                    Text("Use a personal access token instead", color = AppColors.primary)
                }
            } else {
                TextButton(onClick = { usePat = false }) { Text("Sign in with GitHub instead", color = AppColors.primary) }
            }
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
            } else if (usePat) {
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

        SectionCard(title = "Restore numbers from GitHub") {
            Muted("Brings back weights, reps and treadmill sessions from your GitHub repo, e.g. on a new phone. Feelings and knee scores come from Drive. Sessions you already have are never changed; only missing days are added.")
            if (appData.records.isEmpty() && GithubSync.isConfigured(ctx) && restore == GithubSync.RestoreState.Idle) {
                Text("This phone has no sessions yet. Restore your numbers?", color = AppColors.primary)
            }
            when (val r = restore) {
                GithubSync.RestoreState.Idle -> {}
                GithubSync.RestoreState.Loading -> Muted("Reading your GitHub repo...")
                is GithubSync.RestoreState.Preview -> {
                    val fresh = PublicImport.newOnly(appData.records, r.imported).size
                    Text("Found ${r.imported.size} sessions ($fresh new)", color = AppColors.textPrimary, style = MaterialTheme.typography.titleMedium)
                    SecondaryButton("Add $fresh sessions", onClick = {
                        val add = GithubSync.restoreToAdd(vm.data.value.records)
                        if (add.isNotEmpty()) vm.replaceData(vm.data.value.copy(records = vm.data.value.records + add))
                        GithubSync.restoreFinished(add.size)
                    }, enabled = fresh > 0, modifier = Modifier.fillMaxWidth(), accent = AppColors.primary)
                    SecondaryButton("Cancel", onClick = { GithubSync.restoreReset() }, modifier = Modifier.fillMaxWidth(), accent = AppColors.textMuted)
                }
                is GithubSync.RestoreState.Failed -> Text(r.message, color = AppColors.energy)
                is GithubSync.RestoreState.Done -> Text("Added ${r.added} sessions.", color = AppColors.primary)
            }
            if (restore !is GithubSync.RestoreState.Preview) {
                SecondaryButton(
                    "Restore numbers from GitHub", onClick = { GithubSync.previewRestore(ctx, vm.data.value.records) },
                    enabled = GithubSync.isConfigured(ctx) && restore != GithubSync.RestoreState.Loading,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        DriveBackupCard(vm)

        SecondaryButton("Back", onClick = { vm.home() }, modifier = Modifier.fillMaxWidth())
    }
}
