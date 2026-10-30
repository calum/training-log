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

// ---------------- Gym ----------------

@Composable
fun GymScreen(vm: AppViewModel) {
    val g by vm.gym.collectAsState()
    val live = g ?: run { vm.home(); return }
    KeepScreenOn()
    val ctx = LocalContext.current
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }

    var flash by remember { mutableStateOf(false) }
    var remaining by remember { mutableIntStateOf(0) }
    LaunchedEffect(live.rest) {
        val rest = live.rest ?: run { remaining = 0; return@LaunchedEffect }
        while (true) {
            val left = ((rest.endsAt - SystemClock.elapsedRealtime()) / 1000).toInt() + 1
            remaining = left.coerceAtLeast(0)
            if (left <= 0) break
            delay(250)
        }
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
        flash = true
        delay(800)
        flash = false
        vm.clearRest()
    }

    Box(Modifier.fillMaxSize().background(if (flash) Color(0xFF2B4A3F) else Color.Transparent)) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Title("Gym ${live.plan.dayLabel}${if (live.plan.deload) " · deload week" else ""}")
                if (live.plan.dropped.isNotEmpty()) Muted("Trimmed to fit your time: ${live.plan.dropped.joinToString { it.name }}")
                live.exercises.forEachIndexed { ei, e -> ExerciseCard(vm, ei, e) }
                Button(onClick = { vm.finishGym() }, Modifier.fillMaxWidth().height(56.dp)) { Text("Finish session", fontSize = 18.sp) }
                TextButton(onClick = { vm.home() }) { Text("Discard") }
            }
            if (live.rest != null && remaining > 0) {
                Surface(color = M3.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Rest  ${remaining / 60}:${"%02d".format(remaining % 60)}", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { vm.clearRest() }) { Text("Skip") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExerciseCard(vm: AppViewModel, ei: Int, e: LiveExercise) {
    var showSwap by remember { mutableStateOf(false) }
    val ex = e.planned.exercise
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(ex.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (ex.role == Role.ACCESSORY) TextButton(onClick = { showSwap = !showSwap }) { Text("Swap") }
            }
            Muted(e.planned.prescription.note)
            if (showSwap) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.swapOptions(ex).forEach { alt ->
                        FilledTonalButton(onClick = { vm.swap(ei, alt.id); showSwap = false }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                            Text(alt.name, fontSize = 12.sp, maxLines = 2)
                        }
                    }
                }
            }
            if (e.warmups.isNotEmpty()) {
                Text("Warm-up", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    e.warmups.forEachIndexed { wi, w ->
                        FilterChip(selected = w.done, onClick = { vm.toggleWarmup(ei, wi) }, label = { Text("${fmt(w.kg)}×${w.reps}") })
                    }
                }
            }
            e.sets.forEachIndexed { si, s -> SetRow(vm, ei, si, s) }
            if (e.askMiss) {
                Text("A few reps were missed. How did it feel?", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.setMiss(ei, MissReason.ROUGH_DAY) }) { Text("Rough day") }
                    OutlinedButton(onClick = { vm.setMiss(ei, MissReason.AT_LIMIT) }) { Text("At my limit") }
                }
            }
        }
    }
}

@Composable
private fun SetRow(vm: AppViewModel, ei: Int, si: Int, s: LiveSet) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${si + 1}", Modifier.width(18.dp), color = M3.colorScheme.onSurfaceVariant)
            Stepper("${fmt(s.kg)} kg", { vm.adjustSet(ei, si, dKg = -2.5) }, { vm.adjustSet(ei, si, dKg = 2.5) }, Modifier.weight(1f), s.done)
            Stepper("${s.reps} reps", { vm.adjustSet(ei, si, dReps = -1) }, { vm.adjustSet(ei, si, dReps = 1) }, Modifier.weight(1f), s.done)
            Button(
                onClick = { vm.doneSet(ei, si) },
                colors = if (s.done) ButtonDefaults.buttonColors(containerColor = M3.colorScheme.secondaryContainer, contentColor = M3.colorScheme.onSecondaryContainer) else ButtonDefaults.buttonColors(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
            ) { Text(if (s.done) "Done ✓" else "Done") }
        }
        if (s.done) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Muted("Reps in reserve:")
                listOf(0, 1, 2, 3).forEach { r ->
                    FilterChip(selected = s.rir == r, onClick = { vm.setRir(ei, si, r) }, label = { Text(if (r == 3) "3+" else "$r") })
                }
            }
        }
    }
}

@Composable
private fun Stepper(label: String, minus: () -> Unit, plus: () -> Unit, modifier: Modifier, dim: Boolean) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        TextButton(onClick = minus, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), modifier = Modifier.width(32.dp)) { Text("−", fontSize = 22.sp) }
        Text(label, fontSize = 16.sp, color = if (dim) M3.colorScheme.onSurfaceVariant else M3.colorScheme.onSurface)
        TextButton(onClick = plus, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), modifier = Modifier.width(32.dp)) { Text("+", fontSize = 22.sp) }
    }
}

