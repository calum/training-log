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

