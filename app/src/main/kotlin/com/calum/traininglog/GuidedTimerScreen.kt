package com.calum.traininglog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Placeholder: replaced by the guided easy/interval timer. */
@Composable
fun GuidedTimerScreen(vm: AppViewModel, session: com.calum.traininglog.core.PlannedSession) {
    Column(Modifier.padding(16.dp)) {
        Text(session.title)
        TextButton(onClick = { vm.home() }) { Text("Back") }
    }
}
