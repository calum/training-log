package com.calum.traininglog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.calum.traininglog.core.Rewards
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.AppType
import com.calum.traininglog.ui.BadgeTile
import com.calum.traininglog.ui.HeroCard
import com.calum.traininglog.ui.Pill
import com.calum.traininglog.ui.SecondaryButton
import com.calum.traininglog.ui.SectionCard
import com.calum.traininglog.ui.Spacing
import com.calum.traininglog.ui.StatTile
import com.calum.traininglog.ui.XpBar
import java.time.LocalDate

@Composable
fun TrophyScreen(vm: AppViewModel) {
    val data by vm.data.collectAsState()
    val r = Rewards.summary(data.records, LocalDate.now())
    val prs = r.prs.sortedByDescending { it.date }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item { SecondaryButton("Back", onClick = { vm.home() }) }
        item {
            HeroCard(brush = AppColors.gradientReward) {
                Text("LEVEL ${r.level}", style = AppType.eyebrow, color = AppColors.onHero.copy(alpha = 0.8f))
                Text(r.levelName, style = MaterialTheme.typography.headlineLarge, color = AppColors.onHero)
                Spacer(Modifier.height(Spacing.md))
                XpBar(r.xpIntoLevel, r.xpForNextLevel, r.level, r.levelName)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                StatTile("Sessions", "${r.totalSessions}", modifier = Modifier.weight(1f), accent = AppColors.primary)
                StatTile("Longest streak", "${r.longestStreak}", modifier = Modifier.weight(1f), unit = "weeks", accent = AppColors.energy)
            }
        }
        item {
            Text(
                "Badges  ${r.unlockedBadges.size}/${r.badges.size}",
                style = MaterialTheme.typography.titleLarge, color = AppColors.textPrimary,
            )
        }
        items(r.badges.chunked(2), key = { it.first().id }) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                row.forEach { b ->
                    BadgeTile(b.title, b.unlocked, Modifier.weight(1f), subtitle = if (b.unlocked) "Earned ${b.unlockedOn}" else b.description)
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item { Text("Personal records", style = MaterialTheme.typography.titleLarge, color = AppColors.textPrimary, modifier = Modifier.padding(top = Spacing.sm)) }
        if (prs.isEmpty()) {
            item { Text("Your first personal record is waiting for you.", color = AppColors.textMuted) }
        }
        items(prs) { p ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(p.text, style = MaterialTheme.typography.titleSmall, color = AppColors.textPrimary, modifier = Modifier.weight(1f))
                    Pill("${p.date}", color = AppColors.energy)
                }
            }
        }
        item { Spacer(Modifier.navigationBarsPadding().height(Spacing.lg)) }
    }
}
