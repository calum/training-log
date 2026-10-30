package com.calum.traininglog

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.core.TreadmillStyle
import com.calum.traininglog.ui.BigActionButton
import com.calum.traininglog.ui.BigNumberStepper
import com.calum.traininglog.ui.SecondaryButton
import com.calum.traininglog.ui.SectionCard

// ---------------- Manual log ----------------

private fun whenLabel(d: Int) = when (d) { 0 -> "Today"; 1 -> "Yesterday"; else -> "$d days ago" }

@Composable
fun ManualLogScreen(vm: AppViewModel) {
    var type by remember { mutableStateOf(SessionType.TREADMILL) }
    var daysAgo by remember { mutableIntStateOf(0) }
    var minutes by remember { mutableIntStateOf(30) }
    var style by remember { mutableStateOf(TreadmillStyle.STEADY) }
    var note by remember { mutableStateOf("") }
    Page {
        Title("Log a session")
        Muted("Already done it? Add it here without starting an activity.")
        SectionCard(title = "What was it?") {
            ChipFlow(listOf(SessionType.TREADMILL, SessionType.CORE, SessionType.HIKE), type, ::typeName) { type = it }
        }
        SectionCard(title = "When?") {
            ChipFlow((0..5).toList(), daysAgo, ::whenLabel) { daysAgo = it }
            BigNumberStepper(
                value = daysAgo.toString(), unit = if (daysAgo == 1) "day ago" else "days ago",
                onMinus = { daysAgo = maxOf(0, daysAgo - 1) },
                onPlus = { daysAgo = minOf(14, daysAgo + 1) },
                label = "Up to 14 days back",
            )
        }
        SectionCard(title = "How long?") {
            BigNumberStepper(
                value = minutes.toString(), unit = "min",
                onMinus = { minutes = maxOf(5, minutes - 5) },
                onPlus = { minutes = minOf(300, minutes + 5) },
            )
            ChipFlow(listOf(10, 15, 20, 30, 45, 60, 90, 120, 180), minutes, { "$it min" }) { minutes = it }
        }
        if (type == SessionType.TREADMILL) {
            SectionCard(title = "Style") {
                ChipFlow(TreadmillStyle.entries, style, { it.label }) { style = it }
            }
        }
        OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
        BigActionButton("Add to log", onClick = { vm.saveManual(type, daysAgo, minutes, style, note) })
        Muted("Gym sessions are logged live so each set's weight and effort feed your progression.")
        SecondaryButton("Back", onClick = { vm.home() }, modifier = Modifier.fillMaxWidth())
    }
}
