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

private val Mint = Color(0xFF7FD1AE)
private val colors = darkColorScheme(
    primary = Mint, onPrimary = Color(0xFF00382A), background = Color(0xFF101214), onBackground = Color(0xFFE6E8EA),
    surface = Color(0xFF181B1E), onSurface = Color(0xFFE6E8EA), surfaceVariant = Color(0xFF23282C), onSurfaceVariant = Color(0xFFB8BEC4),
    secondaryContainer = Color(0xFF2B4A3F), onSecondaryContainer = Color(0xFFD5F2E6),
)

@Composable
fun AppRoot(vm: AppViewModel, inPip: Boolean) {
    M3(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = M3.colorScheme.background) {
            val screen by vm.screen.collectAsState()
            if (inPip) {
                PlayerContent(vm, compact = true)
                return@Surface
            }
            Box(Modifier.statusBarsPadding()) {
                when (val s = screen) {
                    Screen.Home -> HomeScreen(vm)
                    Screen.Start -> StartScreen(vm)
                    Screen.Gym -> GymScreen(vm)
                    Screen.TreadmillSetup -> TreadmillSetupScreen(vm)
                    Screen.TreadmillPlayer -> PlayerContent(vm, compact = false)
                    Screen.TreadmillDone -> TreadmillDoneScreen(vm)
                    is Screen.Light -> LightScreen(vm, s.type)
                    Screen.Manual -> ManualLogScreen(vm)
                    Screen.Export -> ExportScreen(vm)
                }
            }
        }
    }
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun Title(text: String) = Text(text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) =
    Text(text, color = M3.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = modifier)

@Composable
private fun Page(content: @Composable () -> Unit) =
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }

private fun typeName(t: SessionType) = when (t) {
    SessionType.GYM -> "Gym"; SessionType.TREADMILL -> "Treadmill"; SessionType.CORE -> "Core"; SessionType.HIKE -> "Hike / walk"
}

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

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { o -> FilterChip(selected = o == selected, onClick = { onPick(o) }, label = { Text(label(o), fontSize = 16.sp) }) }
            }
        }
    }
}

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

private fun fmt(x: Double) = if (x % 1.0 == 0.0) x.toInt().toString() else x.toString()

// ---------------- Treadmill ----------------

@Composable
fun TreadmillSetupScreen(vm: AppViewModel) {
    val plan by vm.pendingTreadmill.collectAsState()
    val p = plan ?: run { vm.home(); return }
    Page {
        Title("${p.style.label}, ${p.totalSec / 60} min")
        Muted("Speed and incline are changed by hand. You get a beep about 15 seconds before each change, and again when it's time.")
        p.segments.forEach { s ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${s.seconds / 60}:${"%02d".format(s.seconds % 60)}  ${s.label}", color = M3.colorScheme.onSurfaceVariant)
                Text("${s.speedKmh} km/h · ${s.inclinePct.toInt()}%")
            }
        }
        Button(onClick = { vm.startTreadmill() }, Modifier.fillMaxWidth().height(64.dp)) { Text("Start", fontSize = 20.sp) }
        OutlinedButton(onClick = { vm.otherStyle() }, Modifier.fillMaxWidth()) { Text("Different style") }
        TextButton(onClick = { vm.go(Screen.Start) }) { Text("Back") }
    }
}

@Composable
fun PlayerContent(vm: AppViewModel, compact: Boolean) {
    val ui by TreadmillEngine.ui.collectAsState()
    val s = ui.state
    if (s == null) {
        if (!compact) vm.home()
        return
    }
    if (!compact) KeepScreenOn()
    val bg = if (ui.flashing) Color(0xFFF2F2F2) else Color.Transparent
    val fg = if (ui.flashing) Color(0xFF101214) else M3.colorScheme.onBackground
    val muted = if (ui.flashing) Color(0xFF404448) else M3.colorScheme.onSurfaceVariant
    val left = "${s.secondsLeft / 60}:${"%02d".format(s.secondsLeft % 60)}"

    Column(
        Modifier.fillMaxSize().background(bg).padding(if (compact) 6.dp else 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        if (compact) {
            Text("${s.current.speedKmh} km/h · ${s.current.inclinePct.toInt()}%", color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(if (ui.finished) "Done" else left, color = fg, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            s.next?.let { Text("next ${it.speedKmh}/${it.inclinePct.toInt()}%", color = muted, fontSize = 12.sp) }
            return@Column
        }
        Text(s.current.label.uppercase(), color = muted, fontSize = 16.sp)
        Text("${s.current.speedKmh}", color = fg, fontSize = 96.sp, fontWeight = FontWeight.Bold)
        Text("km/h", color = muted, fontSize = 18.sp)
        Text("${s.current.inclinePct.toInt()}%", color = fg, fontSize = 72.sp, fontWeight = FontWeight.Bold)
        Text("incline", color = muted, fontSize = 18.sp)
        Spacer(Modifier.height(12.dp))
        Text(if (ui.finished) "Session complete" else if (ui.paused) "Paused" else left, color = fg, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        s.next?.let { n ->
            Text("Next: ${n.speedKmh} km/h · ${n.inclinePct.toInt()}%", color = muted, fontSize = 18.sp)
        }
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(progress = { if (ui.totalSec == 0) 0f else s.elapsedSec / ui.totalSec.toFloat() }, Modifier.fillMaxWidth().height(8.dp))
        Muted("${s.elapsedSec / 60} of ${ui.totalSec / 60} min", Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(16.dp))
        if (!ui.finished) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.adjustSegment(-30) }) { Text("−30s") }
                Button(onClick = { vm.togglePause() }, Modifier.width(140.dp).height(52.dp)) { Text(if (ui.paused) "Resume" else "Pause", fontSize = 18.sp) }
                OutlinedButton(onClick = { vm.adjustSegment(30) }) { Text("+30s") }
            }
            TextButton(onClick = { vm.endTreadmill() }) { Text("End session") }
        } else {
            Button(onClick = { vm.endTreadmill() }, Modifier.fillMaxWidth().height(56.dp)) { Text("Finish", fontSize = 18.sp) }
        }
    }
}

@Composable
fun TreadmillDoneScreen(vm: AppViewModel) {
    Page {
        Title("Nice work")
        Text("How did that feel?", fontWeight = FontWeight.SemiBold)
        Muted("This tunes next time's speed and incline.")
        Button(onClick = { vm.saveTreadmill(Effort.TOO_EASY) }, Modifier.fillMaxWidth().height(56.dp)) { Text("Too easy") }
        Button(onClick = { vm.saveTreadmill(Effort.JUST_RIGHT) }, Modifier.fillMaxWidth().height(56.dp)) { Text("Just right") }
        Button(onClick = { vm.saveTreadmill(Effort.TOO_HARD) }, Modifier.fillMaxWidth().height(56.dp)) { Text("Too hard") }
        TextButton(onClick = { vm.saveTreadmill(null) }) { Text("Skip") }
    }
}

// ---------------- Core / hike ----------------

@Composable
fun LightScreen(vm: AppViewModel, type: SessionType) {
    val inputs by vm.inputs.collectAsState()
    var minutes by remember { mutableIntStateOf(minOf(inputs.minutes, if (type == SessionType.CORE) 20 else inputs.minutes)) }
    var note by remember { mutableStateOf("") }
    Page {
        Title(typeName(type))
        Text("How long did you do?", fontWeight = FontWeight.SemiBold)
        ChipRow(listOf(10, 15, 20, 30, 45, 60, 90, 120, 180), minutes, { "$it min" }) { minutes = it }
        OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.saveLight(type, minutes, note) }, Modifier.fillMaxWidth().height(56.dp)) { Text("Log it", fontSize = 18.sp) }
        TextButton(onClick = { vm.go(Screen.Start) }) { Text("Back") }
    }
}

// ---------------- Export ----------------

@Composable
fun ExportScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    var days by remember { mutableStateOf<Int?>(7) }
    val text = vm.exportText(days)
    Page {
        Title("Export for Claude")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = days == 7, onClick = { days = 7 }, label = { Text("Last week") })
            FilterChip(selected = days == 28, onClick = { days = 28 }, label = { Text("Last 4 weeks") })
            FilterChip(selected = days == null, onClick = { days = null }, label = { Text("Everything") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Training export", text))
            }) { Text("Copy") }
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                ctx.startActivity(Intent.createChooser(send, "Share export"))
            }) { Text("Share") }
        }
        Card(Modifier.fillMaxWidth()) { Text(text, Modifier.padding(12.dp), fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
        TextButton(onClick = { vm.home() }) { Text("Back") }
    }
}

// ---------------- Manual log ----------------

@Composable
fun ManualLogScreen(vm: AppViewModel) {
    var type by remember { mutableStateOf(SessionType.TREADMILL) }
    var daysAgo by remember { mutableIntStateOf(0) }
    var minutes by remember { mutableIntStateOf(30) }
    var style by remember { mutableStateOf(com.calum.traininglog.core.TreadmillStyle.STEADY) }
    var note by remember { mutableStateOf("") }
    Page {
        Title("Log a session")
        Muted("Already done it? Add it here without starting an activity.")
        Text("What was it?", fontWeight = FontWeight.SemiBold)
        ChipRow(listOf(SessionType.TREADMILL, SessionType.CORE, SessionType.HIKE), type, ::typeName) { type = it }
        Text("When?", fontWeight = FontWeight.SemiBold)
        ChipRow(listOf(0, 1, 2, 3, 4, 5), daysAgo, { when (it) { 0 -> "Today"; 1 -> "Yesterday"; else -> "$it days ago" } }) { daysAgo = it }
        Text("How long?", fontWeight = FontWeight.SemiBold)
        ChipRow(listOf(10, 15, 20, 30, 45, 60, 90, 120, 180), minutes, { "$it min" }) { minutes = it }
        if (type == SessionType.TREADMILL) {
            Text("Style", fontWeight = FontWeight.SemiBold)
            ChipRow(com.calum.traininglog.core.TreadmillStyle.entries, style, { it.label }) { style = it }
        }
        OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.saveManual(type, daysAgo, minutes, style, note) }, Modifier.fillMaxWidth().height(56.dp)) { Text("Add to log", fontSize = 18.sp) }
        Muted("Gym sessions are logged live so each set's weight and effort feed your progression.")
        TextButton(onClick = { vm.home() }) { Text("Back") }
    }
}
