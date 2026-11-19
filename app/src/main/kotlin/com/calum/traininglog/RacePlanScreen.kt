package com.calum.traininglog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Placeholder: replaced by the race plan screen. */
@Composable
fun RacePlanScreen(vm: AppViewModel) {
    Column(Modifier.padding(16.dp)) {
        Text("Race plan")
        TextButton(onClick = { vm.home() }) { Text("Back") }
    }
}
