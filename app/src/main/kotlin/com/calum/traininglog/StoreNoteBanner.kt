package com.calum.traininglog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** One-time notice from [Store] on Home: data restored from a safety copy, unreadable, or written by a newer app. */
@Composable
fun StoreNoteBanner(vm: AppViewModel, modifier: Modifier = Modifier) {
    val note by vm.storeNote.collectAsState()
    val n = note ?: return
    Card(modifier.fillMaxWidth().testTag("store-note")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(n.message)
            if (n.kind != StoreNote.Kind.TOO_NEW) TextButton(onClick = { vm.dismissStoreNote() }) { Text("OK") }
        }
    }
}
