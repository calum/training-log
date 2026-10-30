package com.calum.traininglog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme as M3
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calum.traininglog.core.Effort
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.MissReason
import com.calum.traininglog.core.Role
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.core.Stats
import com.calum.traininglog.core.SessionOption
import kotlinx.coroutines.delay
import java.time.LocalDate

// ---------------- Start ----------------

@Composable
fun StartScreen(vm: AppViewModel) {
    val inputs by vm.inputs.collectAsState()
    val rec by vm.recommendation.collectAsState()
    Page {
        Title("Today")
        Text("How much time do you have?", fontWeight = FontWeight.SemiBold)
        ChipRow(listOf(20, 30, 45, 60, 90, 120), inputs.minutes, { if (it < 60) "$it min" else if (it == 120) "2 hours" else "$it min" }) { m ->
            vm.setInputs { it.copy(minutes = m) }
        }
        Text("How do you feel?", fontWeight = FontWeight.SemiBold)
        ChipRow(Feeling.entries, inputs.feeling, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { f ->
            vm.setInputs { it.copy(feeling = f) }
        }
        Text("Right knee, 0-10", fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(value = (inputs.knee ?: 0).toFloat(), onValueChange = { v -> vm.setInputs { it.copy(knee = v.toInt()) } },
                valueRange = 0f..10f, steps = 9, modifier = Modifier.weight(1f))
            Text(inputs.knee?.toString() ?: "-", fontSize = 22.sp, modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = inputs.atGym, onClick = { vm.setInputs { it.copy(atGym = !it.atGym) } }, label = { Text("I'm already at the gym") })
        }
        Button(onClick = { vm.recommend() }, Modifier.fillMaxWidth().height(56.dp)) { Text("What should I do?", fontSize = 18.sp) }

        rec?.let { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Suggested", color = Mint, fontWeight = FontWeight.SemiBold)
                    Text(r.primary.label, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Muted(r.reason)
                    Button(onClick = { vm.begin(r.primary.type, r.primary.minutes, r.primary.style) }, Modifier.fillMaxWidth()) {
                        Text(if (r.primary.type == null) "OK, rest" else "Let's go")
                    }
                    r.alternative?.let { a -> AlternativeButton(vm, a) }
                }
            }
        }
        Muted("Or pick it yourself:")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in SessionType.entries) {
                FilledTonalButton(onClick = { vm.begin(t) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) {
                    Text(typeName(t), fontSize = 13.sp, maxLines = 1)
                }
            }
        }
        TextButton(onClick = { vm.home() }) { Text("Back") }
    }
}

@Composable
private fun AlternativeButton(vm: AppViewModel, a: SessionOption) {
    OutlinedButton(onClick = { vm.begin(a.type, a.minutes, a.style) }, Modifier.fillMaxWidth()) { Text("Instead: ${a.label}") }
}

