package com.calum.traininglog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.calum.traininglog.core.Blocks
import com.calum.traininglog.core.ExerciseLibrary
import com.calum.traininglog.core.roundTo
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.Pill
import com.calum.traininglog.ui.SectionCard
import com.calum.traininglog.ui.Spacing
import java.time.LocalDate

/** Thin rounded progress bar in the design-system colours. */
@Composable
internal fun BlockBar(fraction: Double, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = AppColors.primary) {
    Box(modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(AppColors.track)) {
        Box(Modifier.fillMaxWidth(fraction.toFloat().coerceIn(0f, 1f)).height(10.dp).clip(CircleShape).background(color))
    }
}

/**
 * Compact current-block card for Home. Draws nothing without any block; a gentle prompt when blocks exist
 * but none is active.
 */
@Composable
fun BlockHomeCard(vm: AppViewModel) {
    val data by vm.data.collectAsState()
    val today = remember { LocalDate.now() }
    val state = data.profile.blocks
    val cur = Blocks.current(state, today)
    if (cur == null) {
        if (state.blocks.isNotEmpty()) {
            SectionCard(title = "Ready for a fresh block?", onClick = { vm.go(Screen.Blocks) }) {
                Text(
                    "Your last block is done. A new short goal keeps things fresh.",
                    style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
                )
                Pill("Start a block", color = AppColors.primary, filled = true)
            }
        }
        return
    }
    val p = remember(cur, data.records, today) { Blocks.progress(cur, data.records, today) }
    val name = ExerciseLibrary.nameOf(cur.headline.exerciseId)
    SectionCard(
        title = cur.name,
        onClick = { vm.go(Screen.Blocks) },
        action = { Pill("Week ${p.weekNumber} of ${p.weeks}", color = AppColors.reward) },
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                "$name ${fmt(p.currentKg)} of ${fmt(p.targetKg)} kg",
                style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary, modifier = Modifier.weight(1f),
            )
        }
        BlockBar(p.headlineFraction)
        val inc = ExerciseLibrary.find(cur.headline.exerciseId)?.incrementKg ?: 2.5
        if (!p.headlineReached) {
            val next = minOf(p.targetKg, roundTo(p.currentKg, 2.5) + inc)
            Text("Next milestone: ${fmt(next)} kg", style = MaterialTheme.typography.bodyMedium, color = AppColors.primary)
        }
        Text(p.paceText, style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
    }
}
