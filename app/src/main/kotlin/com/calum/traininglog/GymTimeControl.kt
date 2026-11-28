package com.calum.traininglog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.SelectChip
import com.calum.traininglog.ui.Spacing

/**
 * "How much time do you have for lifting?": large chips (lifting minutes) and, for a planned session with a hip prehab,
 * the "Include the hip prehab" switch. Shown on the guided overview and (compact) at the top of the gym list until the first set.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GymTimeControl(t: GymGuide.TimeControl, onWindow: (Int) -> Unit, onPrehab: (Boolean) -> Unit, compact: Boolean = false, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            if (compact) "Time for lifting" else "How much time do you have for lifting?",
            style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium, color = AppColors.textPrimary,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            t.windows.forEach { w ->
                val label = if (w == t.plannedWindow) "$w min · planned" else "$w min"
                SelectChip(label, selected = w == t.selected, onClick = { onWindow(w) }, modifier = Modifier.heightIn(min = 48.dp), accent = AppColors.info)
            }
        }
        if (t.prehabMinutes > 0) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Column(Modifier.weight(1f)) {
                    Text("Include the hip prehab", style = MaterialTheme.typography.titleSmall, color = AppColors.textPrimary)
                    Text(
                        if (t.prehabOn) "About ${t.prehabMinutes} min after the lifts. The chips above are lifting time only."
                        else "Off: the ${t.prehabMinutes} minutes go to lifting.",
                        style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
                    )
                }
                Switch(checked = t.prehabOn, onCheckedChange = onPrehab)
            }
        }
        t.change?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.primary) }
    }
}
