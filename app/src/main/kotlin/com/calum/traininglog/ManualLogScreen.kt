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
