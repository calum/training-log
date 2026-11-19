package com.calum.traininglog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calum.traininglog.core.HyroxStation as Station

/** Placeholder: replaced by the real Hyrox hub. */
@Composable
fun HyroxScreen(vm: AppViewModel) {
    Column(Modifier.padding(16.dp)) {
        Text("Hyrox hub")
        TextButton(onClick = { vm.home() }) { Text("Back") }
    }
}
