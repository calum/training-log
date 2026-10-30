package com.calum.traininglog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.BigActionButton
import com.calum.traininglog.ui.SecondaryButton
import com.calum.traininglog.ui.SectionCard
import com.calum.traininglog.ui.Spacing

// ---------------- Export ----------------

@Composable
fun ExportScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    var days by remember { mutableStateOf<Int?>(7) }
    val text = vm.exportText(days)
    Page {
        Title("Export for Claude")
        ChipFlow(
            listOf<Int?>(7, 28, null), days,
            { when (it) { 7 -> "Last week"; 28 -> "Last 4 weeks"; else -> "Everything" } },
        ) { days = it }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            BigActionButton("Copy", modifier = Modifier.weight(1f), onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Training export", text))
            })
            SecondaryButton("Share", modifier = Modifier.weight(1f), onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                ctx.startActivity(Intent.createChooser(send, "Share export"))
            })
        }
        SectionCard(title = "Preview") {
            SelectionContainer {
                Text(text, color = AppColors.textPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        }
        SecondaryButton("Back", onClick = { vm.home() }, modifier = Modifier.fillMaxWidth())
    }
}
