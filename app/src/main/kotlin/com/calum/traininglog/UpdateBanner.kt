package com.calum.traininglog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shows update state from [UpdateManager]; draws nothing when there's nothing to say. Drop it anywhere. */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val state by UpdateManager.state.collectAsState()
    when (val s = state) {
        UpdateState.Idle -> Unit
        UpdateState.Checking -> BannerCard(modifier) { Text("Checking for updates…") }
        UpdateState.UpToDate -> BannerCard(modifier) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("You're on the latest version (${UpdateManager.currentVersion(ctx)}).")
                TextButton(onClick = { UpdateManager.dismiss() }) { Text("OK") }
            }
        }
        is UpdateState.Available -> BannerCard(modifier) {
            Text("Update available: v${s.version}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Your data stays on the phone. Android will ask you to confirm.", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { UpdateManager.install(ctx) }) { Text("Update now") }
                TextButton(onClick = { UpdateManager.dismiss() }) { Text("Later") }
            }
        }
        is UpdateState.Downloading -> BannerCard(modifier) {
            Text("Downloading v${s.version}… ${s.percent}%")
            LinearProgressIndicator(progress = { s.percent / 100f }, Modifier.fillMaxWidth())
        }
        is UpdateState.NeedsPermission -> BannerCard(modifier) {
            Text("Allow Training Log to install updates in the settings screen that just opened, then come back and tap Update again.")
            Row { Button(onClick = { UpdateManager.check(ctx, quiet = false) }) { Text("Check again") } }
        }
        is UpdateState.Installing -> BannerCard(modifier) { Text("Installing v${s.version}… confirm if Android asks.") }
        is UpdateState.Failed -> BannerCard(modifier) {
            Text(s.message)
            TextButton(onClick = { UpdateManager.dismiss() }) { Text("Dismiss") }
        }
    }
}

@Composable
private fun BannerCard(modifier: Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}
