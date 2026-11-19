package com.calum.traininglog

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.ui.BigActionButton
import com.calum.traininglog.ui.BigNumberStepper
import com.calum.traininglog.ui.SecondaryButton
import com.calum.traininglog.ui.SectionCard

// ---------------- Hike / walk quick log (the guided stopwatch is HikeScreen; core has its own guided screen) ----------------

@Composable
fun LightScreen(vm: AppViewModel, type: SessionType) {
    // A hike or walk is a guided stopwatch; the quick log below is one tap away for one already done.
    var manual by remember { mutableStateOf(false) }
    if (type == SessionType.HIKE && !manual) { HikeScreen(vm, onManual = { manual = true }); return }
    val inputs by vm.inputs.collectAsState()
    var minutes by remember { mutableIntStateOf(inputs.minutes) }
    var note by remember { mutableStateOf("") }
    Page {
        Title(typeName(type))
        SectionCard(title = "How long did you do?") {
            BigNumberStepper(
                value = minutes.toString(), unit = "min",
                onMinus = { minutes = maxOf(5, minutes - 5) },
                onPlus = { minutes = minOf(300, minutes + 5) },
            )
            ChipFlow(listOf(10, 15, 20, 30, 45, 60, 90, 120, 180), minutes, { "$it min" }) { minutes = it }
        }
        OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
        BigActionButton("Log it", onClick = { vm.saveLight(type, minutes, note) })
        SecondaryButton("Back", onClick = { vm.backOr(Screen.Start) }, modifier = Modifier.fillMaxWidth())
    }
}
