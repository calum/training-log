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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calum.traininglog.core.DayStatus
import com.calum.traininglog.core.ExerciseLibrary
import com.calum.traininglog.core.RestDays
import com.calum.traininglog.core.HealthLevel
import com.calum.traininglog.core.PlanHealth
import com.calum.traininglog.core.PlannedSession
import com.calum.traininglog.core.Coach
import com.calum.traininglog.core.Profile
import com.calum.traininglog.core.RacePlan
import com.calum.traininglog.core.RacePlanner
import com.calum.traininglog.core.Rewards
import com.calum.traininglog.core.SessionKind
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
    val today = LocalToday.current ?: LocalDate.now()
    val profile = data.profile
    val racePlan = remember(profile, data.records, today) { Coach.racePlan(profile, data.records, today) }
    val plannedToday = racePlan?.let { RacePlanFormat.todaysOpenSession(it, data.records, today) }
    val health = remember(racePlan, data.records, profile, today) { racePlan?.takeIf { !it.maintenance }?.let { PlanHealth.assess(it, data.records, profile, today) } }
    val rewards = Rewards.summary(data.records, today, profile.settings)
    val view = WeekPlanner.plan(data.records, profile, today, profile.settings)
    // The ring, its number and its headline all come from Coach: the plan's sessions while a race plan is on.
    val goodWeek = remember(racePlan, data.records, profile, today) { Coach.goodWeek(racePlan, data.records, profile, today) }
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
            racePlan?.let { rp ->
                if (health?.level == HealthLevel.SUGGEST) item { ReplanCard(health, rp, data.records, profile, today, { f -> vm.updateProfile(f) }) }
            }
            // One Today card: what to do, for how long, and Start. It replaces the Hyrox card, the big Start button and
            // the NEXT UP card, and it comes from Coach so it can never disagree with anything else on screen.
            item {
                if (racePlan != null) {
                    TodayCard(
                        racePlan, data.records, profile, today, plannedToday, restToday,
                        onOpen = { vm.go(Screen.RacePlan) }, onStart = { vm.launchPlanned(it) }, onProfile = { f -> vm.updateProfile(f) },
                        onFreeChoice = { vm.go(Screen.Start) },
                        onTrainAnyway = { vm.updateProfile { RestDays.trainAnyway(it, today) }; vm.go(Screen.Start) },
                    )
                } else {
                    LegacyTodayCard(
                        Coach.nextUpText(null, data.records, profile, today), restToday,
                        onStart = { vm.go(Screen.Start) },
                        onTrainAnyway = { vm.updateProfile { RestDays.trainAnyway(it, today) }; vm.go(Screen.Start) },
                    )
                }
            }
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        ProgressRing(
                            progress = goodWeek.progress.toFloat(),
                            bonusProgress = goodWeek.bonus.toFloat(),
                            size = 76.dp, strokeWidth = 7.dp,
                        ) {
                            Text(
                                "${goodWeek.done}/${goodWeek.total}", Modifier.align(Alignment.Center),
                                style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary,
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(if (restToday) "Rest day" else "This week", style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary)
                            Text(
                                if (restToday) "Recharge, you've earned it." else goodWeek.headline(view.openDaysLeft) + if (goodWeek.bonus > 0.0) ". Your extra sessions are a bonus." else "",
                                style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
                            )
                        }
                    }
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
                HeroCard {
                    XpBar(rewards.xpIntoLevel, rewards.xpForNextLevel, rewards.level, rewards.levelName)
                    Spacer(Modifier.height(Spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Pill("${rewards.activeWeekStreak} week streak", color = AppColors.energy, filled = true)
                        Pill("${rewards.restTokens} rest token${if (rewards.restTokens == 1) "" else "s"}", color = AppColors.info)
                    }
                }
            }
            item { BlockHomeCard(vm) }
            item {
                var more by remember { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        SecondaryButton("Knee care · 15 min", onClick = { vm.launchKneeCare() }, accent = AppColors.primary, modifier = Modifier.weight(1f))
                        SecondaryButton("Just calf raises · 8 min", onClick = { vm.launchKneeCare(calfOnly = true) }, accent = AppColors.energy, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        SecondaryButton("Plan", onClick = { vm.go(Screen.Plan) }, modifier = Modifier.weight(1f))
                        SecondaryButton("Progress", onClick = { vm.go(Screen.Progress) }, accent = AppColors.info, modifier = Modifier.weight(1f))
                        SecondaryButton("Trophies", onClick = { vm.go(Screen.Trophies) }, accent = AppColors.reward, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        SecondaryButton("Hyrox", onClick = { vm.go(Screen.Hyrox) }, accent = AppColors.energy, modifier = Modifier.weight(1f))
                        SecondaryButton("Blocks", onClick = { vm.go(Screen.Blocks) }, modifier = Modifier.weight(1f))
                        SecondaryButton(if (more) "Less" else "More…", onClick = { more = !more }, accent = AppColors.textMuted, modifier = Modifier.weight(1f).testTag("home-more"))
                    }
                    if (more) {
                        SecondaryButton("Log a session I already did", onClick = { vm.go(Screen.Manual) }, modifier = Modifier.fillMaxWidth())
                        SecondaryButton("Settings", onClick = { vm.go(Screen.Settings) }, modifier = Modifier.fillMaxWidth())
                        SecondaryButton("Export for Claude", onClick = { vm.go(Screen.Export) }, modifier = Modifier.fillMaxWidth())
                        SecondaryButton("Backup & sync", onClick = { vm.go(Screen.Backup) }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { UpdateManager.check(ctx, quiet = false) }, Modifier.align(Alignment.CenterHorizontally)) {
                            Text("Check for updates (v${UpdateManager.currentVersion(ctx)})", color = AppColors.textMuted)
                        }
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
            if (data.trash.isNotEmpty()) {
                item {
                    SectionCard(title = "Recently deleted") {
                        Text(
                            "Kept for ${com.calum.traininglog.core.Trash.KEEP_DAYS} days in case it was a slip. Restore puts it back exactly as it was.",
                            style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
                        )
                        data.trash.sortedByDescending { it.deletedOn }.forEach { t ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                Text(
                                    "${t.record.date}  ${typeName(t.record.type)}",
                                    style = MaterialTheme.typography.bodyLarge, color = AppColors.textPrimary, modifier = Modifier.weight(1f),
                                )
                                SecondaryButton("Restore", onClick = { vm.restoreFromTrash(t.record.id) })
                            }
                        }
                    }
                }
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
            text = { Text("${r.date}  ${typeName(r.type)}. This removes it from your history. You can restore it from Recently deleted for ${com.calum.traininglog.core.Trash.KEEP_DAYS} days.") },
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

/** "today", "tomorrow, Sun 22 Nov" or "Sun 22 Nov": when [date] is, for the Today card. */
private fun whenText(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "today"
    today.plusDays(1) -> "tomorrow, ${RacePlanFormat.shortDay(date)}"
    else -> RacePlanFormat.shortDay(date)
}

/**
 * The Today card: the one place on Home that says what to do. It is today's planned session with a Start button; or,
 * when nothing is planned today, the next one (starting it early is fine); or a calm rest / done message. Under it
 * "I have some time", "Skip today" and "Something different" (the time and energy picker). Tap the header for the plan.
 */
@Composable
private fun TodayCard(
    plan: RacePlan, records: List<SessionRecord>, profile: Profile, today: LocalDate, plannedToday: PlannedSession?, restToday: Boolean,
    onOpen: () -> Unit, onStart: (PlannedSession) -> Unit, onProfile: ((Profile) -> Profile) -> Unit,
    onFreeChoice: () -> Unit, onTrainAnyway: () -> Unit,
) {
    val week = RacePlanner.thisWeek(plan, today)
    val done = if (week != null) RacePlanFormat.doneSessions(week, records, today) else emptySet()
    val counted = if (week != null) RacePlanFormat.counted(week) else emptyList()
    val next = if (week != null) RacePlanFormat.nextSession(week, done, today) else null
    val todays = week?.sessions?.firstOrNull { it.date == today }
    SectionCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            val days = plan.daysToGo(today)
            Text(
                if (plan.maintenance) "TRAINING PLAN" else "HYROX SIM · ${RacePlanFormat.countdown(days).uppercase()}",
                style = AppType.eyebrow, color = AppColors.energy, modifier = Modifier.weight(1f),
            )
            Pill(RacePlanFormat.weekShort(plan, today), color = AppColors.info)
        }
        when {
            week == null -> Text("The plan has finished. Well done.", style = MaterialTheme.typography.titleLarge, color = AppColors.textPrimary)
            plannedToday != null -> {
                Text("TODAY", style = AppType.eyebrow, color = AppColors.primary)
                Text(plannedToday.title, style = MaterialTheme.typography.headlineSmall, color = AppColors.textPrimary)
                Text(
                    "${plannedToday.minutes} min · ${RacePlanFormat.intensityLabel(plannedToday.intensity)} · ${plannedToday.rpeLabel}",
                    style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
                )
                BigActionButton("Start", onClick = { onStart(plannedToday) }, modifier = Modifier.testTag("today-start"))
            }
            todays?.isRaceDay == true -> Text("Race day. Enjoy it.", style = MaterialTheme.typography.titleLarge, color = AppColors.textPrimary)
            todays != null && todays.kind != SessionKind.REST && !todays.isSkipped -> {
                Text("DONE TODAY", style = AppType.eyebrow, color = AppColors.primary)
                Text("Nice work.", style = MaterialTheme.typography.headlineSmall, color = AppColors.textPrimary)
                next?.let { Text("Next: ${it.title}, ${it.minutes} min, ${RacePlanFormat.shortDay(it.date)}. Anything more today is a bonus.", style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted) }
            }
            restToday || todays?.kind == SessionKind.REST -> {
                Text("REST DAY", style = AppType.eyebrow, color = AppColors.info)
                Text("Recharge, you've earned it.", style = MaterialTheme.typography.headlineSmall, color = AppColors.textPrimary)
                next?.let { Text("Next: ${it.title}, ${it.minutes} min, ${RacePlanFormat.shortDay(it.date)}.", style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted) }
                SecondaryButton("I've got time: build me a session", onClick = onTrainAnyway, modifier = Modifier.fillMaxWidth(), accent = AppColors.energy)
            }
            next != null -> {
                RacePlanFormat.kickoffTodayNote(plan, today)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.textPrimary) }
                Text("NEXT · ${whenText(next.date, today).uppercase()}", style = AppType.eyebrow, color = AppColors.primary)
                Text(next.title, style = MaterialTheme.typography.headlineSmall, color = AppColors.textPrimary)
                Text(
                    "${next.minutes} min · ${RacePlanFormat.intensityLabel(next.intensity)} · ${next.rpeLabel}",
                    style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
                )
                if (todays == null || todays.kind == SessionKind.REST) {
                    Text("Nothing planned today, so today is rest or a bonus.", style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
                }
                BigActionButton("Start", onClick = { onStart(next) }, modifier = Modifier.testTag("today-start"))
            }
            else -> Text(
                RacePlanFormat.kickoffTodayNote(plan, today) ?: "Nothing more planned this week. Rest and enjoy it.",
                style = MaterialTheme.typography.bodyLarge, color = AppColors.textPrimary,
            )
        }
        if (week != null && counted.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "${done.size} of ${counted.size} this ${if (week.isLeadIn) "kick-off" else "week"}",
                    style = MaterialTheme.typography.labelMedium, color = AppColors.textMuted,
                )
                // An optional add-on, not one of the sessions: a small chip that opens the knee care routine.
                Pill(week.kneeCare.label, color = AppColors.info)
            }
        } else if (week != null) {
            Pill(week.kneeCare.label, color = AppColors.info)
        }
        RacePlanFormat.kickoffStatus(plan, today)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.info)
        }
        TodayActions(plan, records, profile, today, onStart, onProfile, onFreeChoice = onFreeChoice)
    }
}

/** The Today card when no race or maintenance plan is on: the weekly-target suggestion and the Start button, as before. */
@Composable
private fun LegacyTodayCard(text: String?, restToday: Boolean, onStart: () -> Unit, onTrainAnyway: () -> Unit) {
    SectionCard {
        Text(if (restToday) "REST DAY" else "TODAY", style = AppType.eyebrow, color = if (restToday) AppColors.info else AppColors.primary)
        text?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary) }
        if (restToday) BigActionButton("I've got time: build me a session", onClick = onTrainAnyway, brush = AppColors.gradientEnergy)
        else BigActionButton("Start a session", onClick = onStart)
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
