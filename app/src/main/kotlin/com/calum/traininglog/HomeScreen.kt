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

// ---------------- Home ----------------

@Composable
fun HomeScreen(vm: AppViewModel) {
    val data by vm.data.collectAsState()
    val week = Stats.week(data.records, LocalDate.now())
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Title("Training Log")
            UpdateBanner(Modifier.padding(bottom = 4.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("This week", fontWeight = FontWeight.SemiBold)
                    Text("${week.gym} gym  ·  ${week.treadmill} treadmill" + if (week.other > 0) "  ·  ${week.other} other" else "", fontSize = 20.sp)
                    Muted("Stretch week is 3 gym and 4 treadmill. A good week is 2 and 3-4. Any week counts.")
                }
            }
            Button(onClick = { vm.go(Screen.Start) }, Modifier.fillMaxWidth().height(64.dp)) { Text("Start a session", fontSize = 20.sp) }
            OutlinedButton(onClick = { vm.go(Screen.Manual) }, Modifier.fillMaxWidth()) { Text("Log a session I already did") }
            OutlinedButton(onClick = { vm.go(Screen.Export) }, Modifier.fillMaxWidth()) { Text("Export for Claude") }
            val appCtx = LocalContext.current
            TextButton(onClick = { UpdateManager.check(appCtx, quiet = false) }) { Text("Check for updates (v${UpdateManager.currentVersion(appCtx)})") }
            Spacer(Modifier.height(4.dp))
            Text("History", fontWeight = FontWeight.SemiBold)
            if (data.records.isEmpty()) Muted("Nothing logged yet. Your first session will show up here.")
        }
        itemsIndexed(data.records.sortedByDescending { it.date }.take(30)) { _, r -> RecordRow(r) }
    }
}

@Composable
private fun RecordRow(r: SessionRecord) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("${r.date}  ${typeName(r.type)}${r.dayLabel?.let { " $it" } ?: ""}${if (r.deload) " (deload)" else ""}", fontWeight = FontWeight.SemiBold)
            val detail = when {
                r.exercises.isNotEmpty() -> r.exercises.joinToString(" · ") { e ->
                    val w = e.sets.maxOf { it.weightKg }
                    "${com.calum.traininglog.core.ExerciseLibrary.get(e.exerciseId).name} ${if (w % 1.0 == 0.0) w.toInt() else w}kg"
                }
                r.treadmill != null -> "${r.treadmill!!.style.label}, ${r.treadmill!!.completedSec / 60} min"
                else -> "${r.minutesActual} min"
            }
            Muted(detail)
        }
    }
}

