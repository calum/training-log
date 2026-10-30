package com.calum.traininglog

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calum.traininglog.core.DayStatus
import com.calum.traininglog.core.ExerciseLibrary
import com.calum.traininglog.core.RestDays
import com.calum.traininglog.core.Rewards
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.core.WeekPlanner
import com.calum.traininglog.core.WeekView
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.AppShapes
import com.calum.traininglog.ui.AppType
import com.calum.traininglog.ui.BigActionButton
import com.calum.traininglog.ui.ConfettiBurst
import com.calum.traininglog.ui.DayDot
import com.calum.traininglog.ui.DayState
import com.calum.traininglog.ui.HeroCard
import com.calum.traininglog.ui.Pill
import com.calum.traininglog.ui.ProgressRing
import com.calum.traininglog.ui.SecondaryButton
import com.calum.traininglog.ui.SectionCard
import com.calum.traininglog.ui.Spacing
import com.calum.traininglog.ui.XpBar
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

// ---------------- Home ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(vm: AppViewModel) {
    val data by vm.data.collectAsState()
    val celebration by vm.celebration.collectAsState()
    val ctx = LocalContext.current
    val today = LocalDate.now()
    val profile = data.profile
    val rewards = Rewards.summary(data.records, today, profile.settings)
    val view = WeekPlanner.plan(data.records, profile, today, profile.settings)
    val restToday = RestDays.isRestDay(profile, today)
    val sorted = data.records.sortedByDescending { it.date }.take(30)
    var toDelete by remember { mutableStateOf<SessionRecord?>(null) }
    var weekdayFor by remember { mutableStateOf<LocalDate?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                Text("Training Log", style = MaterialTheme.typography.headlineMedium, color = AppColors.textPrimary)
                UpdateBanner(Modifier.padding(top = Spacing.sm))
                StoreNoteBanner(vm, Modifier.padding(top = Spacing.sm))
            }
            item {
                HeroCard {
                    Text(if (restToday) "REST DAY" else "THIS WEEK", style = AppType.eyebrow, color = AppColors.onHero.copy(alpha = 0.8f))
                    Spacer(Modifier.height(Spacing.md))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ProgressRing(
                            progress = rewards.ring.progress.toFloat(),
                            bonusProgress = rewards.ring.bonus.toFloat(),
                            size = 200.dp, strokeWidth = 16.dp,
                        ) {
                            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${view.goodWeekDone}/${view.goodWeekTotal}", style = AppType.numberL, color = AppColors.onHero)
                                Text("good week", style = MaterialTheme.typography.labelMedium, color = AppColors.onHero.copy(alpha = 0.8f))
                            }
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        if (restToday) "Rest day. Recharge, you've earned it." else view.headline(),
                        style = MaterialTheme.typography.titleMedium, color = AppColors.onHero,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Spacing.md))
                    XpBar(rewards.xpIntoLevel, rewards.xpForNextLevel, rewards.level, rewards.levelName)
                    Spacer(Modifier.height(Spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Pill("${rewards.activeWeekStreak} week streak", color = AppColors.energy, filled = true)
                        Pill("${rewards.restTokens} rest token${if (rewards.restTokens == 1) "" else "s"}", color = AppColors.info)
                    }
                }
            }
            item {
                SectionCard(title = "Your week") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        view.days.forEach { d ->
                            Box(
                                Modifier.weight(1f)
                                    .clip(AppShapes.small)
                                    .combinedClickable(
                                        enabled = d.date >= today,
                                        onClick = { vm.updateProfile { RestDays.toggleDate(it, d.date) } },
                                        onLongClick = { weekdayFor = d.date },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                DayDot(d.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), dayState(d.status))
                            }
                        }
                    }
                    Text(
                        "Tap a day to make it a rest day (or back). Long-press to repeat it every week.",
                        style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
                    )
                }
            }
            item {
                if (restToday) {
                    BigActionButton("I've got time: build me a session", onClick = {
                        vm.updateProfile { RestDays.trainAnyway(it, today) }
                        vm.go(Screen.Start)
                    }, brush = AppColors.gradientEnergy)
                } else {
                    BigActionButton("Start a session", onClick = { vm.go(Screen.Start) })
                }
            }
            nextUp(data.records, view, today)?.let { text -> item { NextUpCard(text) } }
            item { BlockHomeCard(vm) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SecondaryButton("Log a session I already did", onClick = { vm.go(Screen.Manual) }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        SecondaryButton("Plan", onClick = { vm.go(Screen.Plan) }, modifier = Modifier.weight(1f))
                        SecondaryButton("Progress", onClick = { vm.go(Screen.Progress) }, accent = AppColors.info, modifier = Modifier.weight(1f))
                        SecondaryButton("Trophies", onClick = { vm.go(Screen.Trophies) }, accent = AppColors.reward, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        SecondaryButton("Blocks", onClick = { vm.go(Screen.Blocks) }, modifier = Modifier.weight(1f))
                        SecondaryButton("Settings", onClick = { vm.go(Screen.Settings) }, modifier = Modifier.weight(1f))
                    }
                    SecondaryButton("Export for Claude", onClick = { vm.go(Screen.Export) }, modifier = Modifier.fillMaxWidth())
                    SecondaryButton("Backup & sync", onClick = { vm.go(Screen.Backup) }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { UpdateManager.check(ctx, quiet = false) }, Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Check for updates (v${UpdateManager.currentVersion(ctx)})", color = AppColors.textMuted)
                    }
                }
            }
            item { Text("History", style = MaterialTheme.typography.titleLarge, color = AppColors.textPrimary, modifier = Modifier.padding(top = Spacing.sm)) }
            if (sorted.isEmpty()) {
                item { Text("Nothing logged yet. Your first session will show up here.", color = AppColors.textMuted) }
            }
            items(sorted, key = { it.id }) { r ->
                RecordCard(r, isPr = r.id in rewards.prSessionIds, xp = rewards.sessionXp[r.id]?.total, onLongPress = { toDelete = r })
            }
            item { Spacer(Modifier.navigationBarsPadding().height(Spacing.lg)) }
        }

        celebration?.let { c ->
            CelebrationOverlay(c, onDismiss = { vm.dismissCelebration() })
        }
    }

    toDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete this session?") },
            text = { Text("${r.date}  ${typeName(r.type)}. This removes it from your history.") },
            confirmButton = { TextButton(onClick = { vm.deleteRecord(r.id); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Keep it") } },
        )
    }
    weekdayFor?.let { d ->
        val name = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val on = d.dayOfWeek in profile.restWeekdays
        AlertDialog(
            onDismissRequest = { weekdayFor = null },
            title = { Text(if (on) "Stop resting every $name?" else "Rest every $name?") },
            text = { Text(if (on) "$name will count as a normal training day again." else "Repeat every $name: it becomes a rest day each week.") },
            confirmButton = {
                TextButton(onClick = { vm.updateProfile { RestDays.toggleWeekday(it, d.dayOfWeek) }; weekdayFor = null }) {
                    Text(if (on) "Turn off" else "Repeat every $name")
                }
            },
            dismissButton = { TextButton(onClick = { weekdayFor = null }) { Text("Cancel") } },
        )
    }
}

private fun dayState(s: DayStatus) = when (s) {
    DayStatus.DONE -> DayState.Done
    DayStatus.TODAY -> DayState.Today
    DayStatus.OPEN -> DayState.Open
    DayStatus.REST -> DayState.Rest
    DayStatus.REST_BUT_TRAINED -> DayState.RestTrained
    DayStatus.PAST_EMPTY -> DayState.PastEmpty
}

private fun nextUp(records: List<SessionRecord>, view: WeekView, today: LocalDate): String? =
    com.calum.traininglog.core.NextUp.describe(records, view, today)

@Composable
private fun NextUpCard(text: String) {
    SectionCard {
        Text("NEXT UP", style = AppType.eyebrow, color = AppColors.primary)
        Text(text, style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordCard(r: SessionRecord, isPr: Boolean, xp: Int?, onLongPress: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(AppShapes.medium).background(AppColors.surface)
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                "${r.date}  ${typeName(r.type)}${r.dayLabel?.let { " $it" } ?: ""}${if (r.deload) " (deload)" else ""}",
                style = MaterialTheme.typography.titleSmall, color = AppColors.textPrimary, modifier = Modifier.weight(1f),
            )
            if (isPr) Pill("PR", color = AppColors.energy, filled = true)
            if (xp != null) Pill("+$xp XP", color = AppColors.reward)
        }
        val detail = when {
            r.exercises.isNotEmpty() -> r.exercises.joinToString(" · ") { e ->
                val w = e.sets.maxOfOrNull { it.weightKg } ?: 0.0
                "${ExerciseLibrary.nameOf(e.exerciseId, e.customName)} ${if (w % 1.0 == 0.0) w.toInt() else w}kg"
            }
            r.treadmill != null -> "${r.treadmill!!.style.label}, ${r.treadmill!!.completedSec / 60} min"
            else -> "${r.minutesActual} min"
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
    }
}

@Composable
private fun CelebrationOverlay(c: Celebration, onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(AppColors.background.copy(alpha = 0.82f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        HeroCard(Modifier.padding(Spacing.xl), brush = AppColors.gradientReward, onClick = onDismiss) {
            Text("NICE WORK", style = AppType.eyebrow, color = AppColors.onHero.copy(alpha = 0.8f))
            Text("Session done!", style = MaterialTheme.typography.headlineMedium, color = AppColors.onHero)
            Spacer(Modifier.height(Spacing.md))
            c.messages.forEach {
                Text(it, style = MaterialTheme.typography.titleMedium, color = AppColors.onHero, modifier = Modifier.padding(vertical = Spacing.xs))
            }
            Spacer(Modifier.height(Spacing.lg))
            BigActionButton("Awesome", onClick = onDismiss)
        }
        ConfettiBurst(trigger = 1, modifier = Modifier.fillMaxSize())
    }
}
