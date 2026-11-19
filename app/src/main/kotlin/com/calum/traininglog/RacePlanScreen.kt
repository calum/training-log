package com.calum.traininglog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.calum.traininglog.core.IntensityBalance
import com.calum.traininglog.core.Intensity
import com.calum.traininglog.core.PlanWeek
import com.calum.traininglog.core.PlannedSession
import com.calum.traininglog.core.Profile
import com.calum.traininglog.core.RacePhase
import com.calum.traininglog.core.RacePlan
import com.calum.traininglog.core.RacePlanSettings
import com.calum.traininglog.core.RacePlanner
import com.calum.traininglog.core.RunStage
import com.calum.traininglog.core.RunStatus
import com.calum.traininglog.core.SessionKind
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.TimeSplit
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.AppShapes
import com.calum.traininglog.ui.AppType
import com.calum.traininglog.ui.BigActionButton
import com.calum.traininglog.ui.BigNumberStepper
import com.calum.traininglog.ui.HeroCard
import com.calum.traininglog.ui.Pill
import com.calum.traininglog.ui.SecondaryButton
import com.calum.traininglog.ui.SectionCard
import com.calum.traininglog.ui.SelectChip
import com.calum.traininglog.ui.Spacing
import com.calum.traininglog.ui.TrainingTheme
import java.time.LocalDate

/** The race plan: countdown, phase timeline, this week's sessions, balance, running stage, settings and evidence. */
@Composable
fun RacePlanScreen(vm: AppViewModel) {
    val data by vm.data.collectAsState()
    RacePlanContent(
        records = data.records,
        profile = data.profile,
        today = LocalToday.current ?: LocalDate.now(),
        onSettings = { f -> vm.updateProfile { p -> p.copy(settings = p.settings.copy(racePlan = f(p.settings.racePlan).sanitized())) } },
        onStart = { vm.launchPlanned(it) },
        onBack = { vm.home() },
    )
}

@Composable
internal fun RacePlanContent(
    records: List<SessionRecord>,
    profile: Profile,
    today: LocalDate,
    onSettings: ((RacePlanSettings) -> RacePlanSettings) -> Unit,
    onStart: (PlannedSession) -> Unit,
    onBack: () -> Unit,
    allWeeksOpen: Boolean = false,
) {
    val cfg = profile.settings.racePlan
    val plan = remember(cfg, records, today, profile) { RacePlanner.build(cfg, records, today, profile) }
    val week = RacePlanner.thisWeek(plan, today)

    Column(
        Modifier.fillMaxSize().background(AppColors.background).statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text("Race plan", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, color = AppColors.textPrimary)
            SecondaryButton("Back", onBack)
        }

        if (!cfg.enabled) {
            PausedCard { onSettings { it.copy(enabled = true) } }
        } else {
            RaceHero(plan, today, week)
            PhaseTimeline(plan, week, today)
            if (week != null) {
                ThisWeekCard(plan, week, records, today, onStart)
                BalanceCard(records, week)
            } else {
                SectionCard(title = "Race done") {
                    Text(
                        "The plan has run its course. Whatever the day brought, the training behind it is yours. Rest well.",
                        style = MaterialTheme.typography.bodyLarge, color = AppColors.textPrimary,
                    )
                }
            }
            RunningCard(plan.runStatus)
            AllWeeksCard(plan, week, allWeeksOpen)
        }
        SettingsCard(cfg, today, onSettings)
        EvidenceCard()
        Spacer(Modifier.navigationBarsPadding().height(Spacing.xl))
    }
}

// ------------------------------------------------------------------------------------------------ colours and icons

@Composable
private fun phaseColor(p: RacePhase): Color = when (p) {
    RacePhase.BASE -> AppColors.info
    RacePhase.BUILD -> AppColors.primary
    RacePhase.SPECIFIC -> AppColors.energy
    RacePhase.SHARPEN -> AppColors.reward
    RacePhase.TAPER -> AppColors.rewardDeep
    RacePhase.RACE -> AppColors.textPrimary
}

@Composable
private fun intensityColor(i: Intensity): Color = when (i) {
    Intensity.EASY -> AppColors.info
    Intensity.HARD -> AppColors.energy
    Intensity.STRENGTH -> AppColors.reward
}

@Composable
private fun inkOn(): Color = if (AppColors.isDark) Color(0xFF05100B) else Color.White

/** Drawn glyph for a session kind inside a tinted rounded square; a tick replaces it once the session is done. */
@Composable
private fun KindIcon(kind: SessionKind, raceDay: Boolean, color: Color, done: Boolean, modifier: Modifier = Modifier) {
    val tint = if (done) AppColors.primary else color
    Box(
        modifier.size(44.dp).clip(AppShapes.small).background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            if (done) drawTick(tint) else when {
                raceDay || kind == SessionKind.SIM -> drawFlag(tint)
                kind == SessionKind.STRENGTH -> drawDumbbell(tint)
                kind == SessionKind.KEY -> drawBolt(tint)
                kind == SessionKind.LONG_EASY -> drawHills(tint)
                kind == SessionKind.REST -> drawRest(tint)
                else -> drawWave(tint)
            }
        }
    }
}

private fun DrawScope.drawTick(c: Color) {
    val p = Path().apply { moveTo(size.width * 0.12f, size.height * 0.55f); lineTo(size.width * 0.40f, size.height * 0.82f); lineTo(size.width * 0.9f, size.height * 0.22f) }
    drawPath(p, c, style = Stroke(3.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawFlag(c: Color) {
    val w = size.width; val h = size.height
    drawLine(c, Offset(w * 0.2f, h * 0.08f), Offset(w * 0.2f, h * 0.95f), 2.6.dp.toPx(), StrokeCap.Round)
    val flag = Path().apply { moveTo(w * 0.2f, h * 0.1f); lineTo(w * 0.92f, h * 0.3f); lineTo(w * 0.2f, h * 0.55f); close() }
    drawPath(flag, c)
}

private fun DrawScope.drawDumbbell(c: Color) {
    val w = size.width; val h = size.height
    drawLine(c, Offset(w * 0.18f, h * 0.5f), Offset(w * 0.82f, h * 0.5f), 3.dp.toPx(), StrokeCap.Round)
    val r = CornerRadius(2.dp.toPx())
    drawRoundRect(c, Offset(w * 0.02f, h * 0.22f), Size(w * 0.16f, h * 0.56f), r)
    drawRoundRect(c, Offset(w * 0.82f, h * 0.22f), Size(w * 0.16f, h * 0.56f), r)
    drawRoundRect(c, Offset(w * 0.2f, h * 0.34f), Size(w * 0.1f, h * 0.32f), r)
    drawRoundRect(c, Offset(w * 0.7f, h * 0.34f), Size(w * 0.1f, h * 0.32f), r)
}

private fun DrawScope.drawBolt(c: Color) {
    val w = size.width; val h = size.height
    val p = Path().apply {
        moveTo(w * 0.62f, 0f); lineTo(w * 0.16f, h * 0.56f); lineTo(w * 0.47f, h * 0.56f)
        lineTo(w * 0.38f, h); lineTo(w * 0.86f, h * 0.4f); lineTo(w * 0.54f, h * 0.4f); close()
    }
    drawPath(p, c)
}

private fun DrawScope.drawHills(c: Color) {
    val w = size.width; val h = size.height
    val p = Path().apply {
        moveTo(0f, h * 0.88f); lineTo(w * 0.34f, h * 0.24f); lineTo(w * 0.54f, h * 0.58f)
        lineTo(w * 0.7f, h * 0.38f); lineTo(w, h * 0.88f)
    }
    drawPath(p, c, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawWave(c: Color) {
    val w = size.width; val h = size.height
    val p = Path().apply {
        moveTo(0f, h * 0.5f)
        cubicTo(w * 0.2f, h * 0.05f, w * 0.3f, h * 0.05f, w * 0.5f, h * 0.5f)
        cubicTo(w * 0.7f, h * 0.95f, w * 0.8f, h * 0.95f, w, h * 0.5f)
    }
    drawPath(p, c, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
}

private fun DrawScope.drawRest(c: Color) {
    drawLine(c, Offset(size.width * 0.15f, center.y), Offset(size.width * 0.85f, center.y), 3.dp.toPx(), StrokeCap.Round)
}

// ------------------------------------------------------------------------------------------------ hero and timeline

@Composable
private fun RaceHero(plan: RacePlan, today: LocalDate, week: PlanWeek?) {
    val days = plan.daysToGo(today)
    HeroCard {
        Text("HYROX SIMULATION", style = AppType.eyebrow, color = AppColors.onHero.copy(alpha = 0.8f))
        Spacer(Modifier.height(Spacing.sm))
        val number = RacePlanFormat.countdownNumber(days)
        if (number != null) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(number, style = AppType.numberXL, color = AppColors.onHero)
                Text(
                    RacePlanFormat.countdownUnit(days), Modifier.padding(bottom = 10.dp),
                    style = MaterialTheme.typography.headlineSmall, color = AppColors.onHero,
                )
            }
        } else {
            Text(RacePlanFormat.countdown(days), style = AppType.numberL, color = AppColors.onHero)
        }
        Text(RacePlanFormat.raceDateText(plan.race), style = MaterialTheme.typography.titleMedium, color = AppColors.onHero)
        Spacer(Modifier.height(Spacing.md))
        Text(
            RacePlanFormat.weekHeadline(plan, today),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = AppColors.onHero,
        )
        if (week != null) {
            Spacer(Modifier.height(Spacing.xs))
            Text(RacePlanFormat.phasePurpose(week.phase), style = MaterialTheme.typography.bodyMedium, color = AppColors.onHero.copy(alpha = 0.9f))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhaseTimeline(plan: RacePlan, week: PlanWeek?, today: LocalDate) {
    SectionCard(Modifier.testTag("timeline"), title = "The 14 weeks") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Top) {
            plan.weeks.forEach { w ->
                val current = week != null && w.index == week.index
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.fillMaxWidth().height(if (current) 44.dp else 34.dp).padding(top = if (current) 0.dp else 5.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(phaseColor(w.phase).copy(alpha = if (current) 1f else 0.7f))
                            .then(if (current) Modifier.border(2.5.dp, AppColors.textPrimary, RoundedCornerShape(7.dp)) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (w.phase == RacePhase.RACE) {
                            Canvas(Modifier.size(14.dp)) { drawFlag(Color(0xFF05100B)) }
                        } else {
                            Text(
                                "${w.index}", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFF05100B), maxLines = 1,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier.size(5.dp).clip(CircleShape)
                            .background(if (w.deload) AppColors.textMuted else Color.Transparent),
                    )
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            RacePhase.entries.filter { it != RacePhase.RACE }.forEach { p ->
                val first = plan.phaseTimeline.firstOrNull { it.phase == p }
                if (first != null) LegendItem(phaseColor(p), p.label, round = false)
            }
            LegendItem(AppColors.textPrimary, "Race", round = false)
            LegendItem(AppColors.textMuted, "Lighter week", round = true)
        }
        Text(
            if (week == null) "The plan has finished."
            else if (today < plan.start) "Week 1 starts on ${RacePlanFormat.shortDay(plan.start)}. Until then, rest up and look around."
            else "You are in week ${week.index} (${RacePlanFormat.weekRange(week)}). The outlined week is this one.",
            style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
        )
    }
}

@Composable
private fun LegendItem(color: Color, text: String, round: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(if (round) 8.dp else 12.dp).clip(if (round) CircleShape else RoundedCornerShape(3.dp)).background(color))
        Text(text, style = MaterialTheme.typography.labelMedium, color = AppColors.textMuted, maxLines = 1)
    }
}

// ------------------------------------------------------------------------------------------------ this week

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThisWeekCard(plan: RacePlan, week: PlanWeek, records: List<SessionRecord>, today: LocalDate, onStart: (PlannedSession) -> Unit) {
    val done = remember(week, records, today) { RacePlanFormat.doneSessions(week, records, today) }
    val counted = RacePlanFormat.counted(week)
    val next = RacePlanFormat.nextSession(week, done, today)
    var open by remember(week.index) { mutableStateOf(setOfNotNull(next?.date)) }
    val progress = RacePlanner.weekProgress(plan, records, today)
    val started = today >= week.start
    val doneMin = counted.filter { it in done }.sumOf { it.minutes }
    val weekOver = week.end < today

    SectionCard(Modifier.testTag("week"), title = if (started) "This week" else "Coming up: week ${week.index}") {
        Text(week.label, style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary)
        Text(week.focus, style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted)
        week.adjustment?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.info)
        }
        if (started) {
            ProgressBar((progress?.fraction ?: 0.0).toFloat())
            Text(
                RacePlanFormat.weekProgressText(done.size, counted.size, doneMin, week.plannedMinutes, weekOver),
                style = MaterialTheme.typography.bodyMedium, color = AppColors.textPrimary,
            )
        } else {
            Text(
                "${counted.size} sessions, ${week.plannedMinutes} planned minutes.",
                style = MaterialTheme.typography.bodyMedium, color = AppColors.textPrimary,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            week.sessions.sortedBy { it.date }.forEach { s ->
                val state = RacePlanFormat.stateOf(s, done, today)
                SessionRow(
                    s, state, today,
                    expanded = s.date in open,
                    onToggle = { open = if (s.date in open) open - s.date else open + s.date },
                    onStart = { onStart(s) },
                )
            }
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(AppColors.track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(10.dp).clip(CircleShape).background(AppColors.gradientPrimary))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionRow(
    s: PlannedSession, state: SessionState, today: LocalDate,
    expanded: Boolean, onToggle: () -> Unit, onStart: () -> Unit,
) {
    val isRest = s.kind == SessionKind.REST
    val accent = if (isRest) AppColors.info else if (s.isRaceDay) AppColors.energy else intensityColor(s.intensity)
    val dayText = if (s.date == today) "Today" else RacePlanFormat.shortDay(s.date)
    Column(
        Modifier.fillMaxWidth().clip(AppShapes.medium).background(AppColors.surfaceRaised)
            .clickable(onClick = onToggle).padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            KindIcon(s.kind, s.isRaceDay, accent, done = state == SessionState.DONE)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(s.title, style = MaterialTheme.typography.titleSmall, color = AppColors.textPrimary)
                Text(
                    if (isRest) dayText else "$dayText · ${s.minutes} min",
                    style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    when {
                        s.isRaceDay -> Pill("Race day", color = AppColors.energy, filled = true)
                        isRest -> Pill("Rest", color = AppColors.info)
                        else -> Pill(RacePlanFormat.intensityLabel(s.intensity), color = accent)
                    }
                    when (state) {
                        SessionState.DONE -> Pill("Done", color = AppColors.primary, filled = true)
                        SessionState.OPTIONAL -> if (!isRest && !s.isRaceDay) Pill("Optional", color = AppColors.textMuted)
                        SessionState.UPCOMING -> {}
                    }
                }
            }
            Chevron(expanded)
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (!isRest && !s.isRaceDay) {
                    Text(s.rpeLabel + (if (s.hardMinutes > 0) " · about ${s.hardMinutes} of ${s.minutes} min hard" else ""), style = MaterialTheme.typography.labelLarge, color = accent)
                }
                s.detail.forEach { line ->
                    val indent = line.length - line.trimStart().length
                    Text(
                        line.trim(), Modifier.padding(start = if (indent > 0) 12.dp else 0.dp),
                        style = MaterialTheme.typography.bodyMedium, color = AppColors.textPrimary,
                    )
                }
                Text("Why: ${s.why}", style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
                if (s.includesRunning) {
                    Text(
                        "Running leg: stop and swap to the rower or a walk if the knee goes above 3/10.",
                        style = MaterialTheme.typography.bodySmall, color = AppColors.info,
                    )
                }
                if (!isRest && !s.isRaceDay && s.modalityOptions.isNotEmpty()) {
                    Text("CAN BE DONE ON", style = AppType.eyebrow, color = AppColors.textMuted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        s.modalityOptions.forEach { Pill(it.label, color = AppColors.info) }
                    }
                }
                if (state == SessionState.UPCOMING && !isRest && !s.isRaceDay) {
                    BigActionButton("Start", onClick = onStart)
                }
            }
        }
    }
}

@Composable
private fun Chevron(expanded: Boolean) {
    val c = AppColors.textMuted
    Box(Modifier.size(width = 24.dp, height = 44.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(14.dp, 9.dp)) {
            val up = expanded
            val p = Path().apply {
                if (up) { moveTo(0f, size.height); lineTo(size.width / 2, 0f); lineTo(size.width, size.height) }
                else { moveTo(0f, 0f); lineTo(size.width / 2, size.height); lineTo(size.width, 0f) }
            }
            drawPath(p, c, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

// ------------------------------------------------------------------------------------------------ balance

@Composable
private fun BalanceCard(records: List<SessionRecord>, week: PlanWeek) {
    val bal = remember(records, week.start) { IntensityBalance.of(records, week.start) }
    SectionCard(Modifier.testTag("balance"), title = "Easy versus hard") {
        Text(
            "The plan aims for ${bal.targetLabel}: mostly easy endurance time, a little hard.",
            style = MaterialTheme.typography.bodyMedium, color = AppColors.textPrimary,
        )
        SplitRow("This week", bal.week, bal.weekLabel)
        SplitRow("Last 4 weeks", bal.last4Weeks, bal.last4Label)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            LegendItem(AppColors.primary, "Easy", round = false)
            LegendItem(AppColors.energy, "Hard", round = false)
        }
        Text(bal.message, style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted)
        Text(
            "Why: a mostly easy mix (low-intensity-dominant) builds the aerobic base with little injury risk. " +
                "The evidence is moderate: studies show it modestly helps VO2peak, with no single best split for race performance. " +
                "Strength sessions are counted separately.",
            style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
        )
    }
}

@Composable
private fun SplitRow(label: String, split: TimeSplit, text: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), Modifier.weight(1f), style = AppType.eyebrow, color = AppColors.textMuted)
            Text(text ?: "not enough logged yet", style = MaterialTheme.typography.titleSmall, color = AppColors.textPrimary)
        }
        Row(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape).background(AppColors.track)) {
            if (split.enduranceMin > 0) {
                if (split.easyMin > 0) Box(Modifier.weight(split.easyMin.toFloat()).fillMaxSize().background(AppColors.primary))
                if (split.hardMin > 0) Box(Modifier.weight(split.hardMin.toFloat()).fillMaxSize().background(AppColors.energy))
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ running

@Composable
private fun RunningCard(status: RunStatus) {
    SectionCard(Modifier.testTag("running"), title = "Running and the knee") {
        Text(status.message, style = MaterialTheme.typography.bodyLarge, color = AppColors.textPrimary)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            RunStage.entries.forEach { st ->
                val current = st == status.stage
                val passed = st < status.stage
                Row(
                    Modifier.fillMaxWidth().clip(AppShapes.small)
                        .background(if (current) AppColors.primary.copy(alpha = 0.14f) else Color.Transparent)
                        .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    StageDot(current, passed)
                    Column(Modifier.weight(1f)) {
                        Text(
                            shortStage(st), style = MaterialTheme.typography.titleSmall,
                            color = if (current) AppColors.primary else if (passed) AppColors.textPrimary else AppColors.textMuted,
                        )
                        Text(RacePlanFormat.stageBlurb(st), style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
                        if (current) RacePlanFormat.rungText(st, status.rung)?.let { (step, label) ->
                            Text("$step: $label", style = MaterialTheme.typography.labelMedium, color = AppColors.primary)
                        }
                    }
                    if (current) Pill("You are here", color = AppColors.primary, filled = true)
                }
            }
        }
        status.physioNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.info) }
        Text(
            "Moving up: two runs in a row with the knee at 2/10 or less, and a calm week since any 3/10 or more " +
                "(later stages ask for a few more clean runs). Nothing is rushed, and holding a stage is never a setback.",
            style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
        )
        Text(
            "Running is optional. If it is not ready by the event, the plan swaps in the rower, SkiErg and incline walking for the run legs.",
            style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
        )
        Text(
            "Not medical advice. If knee pain persists, see your physio.",
            style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
        )
    }
}

private fun shortStage(s: RunStage) = when (s) {
    RunStage.NONE -> "None"
    RunStage.RUN_WALK -> "Run-walk"
    RunStage.CONTINUOUS -> "Continuous"
    RunStage.INTERVALS -> "Intervals"
    RunStage.RACE_RUNS -> "Race runs"
}

@Composable
private fun StageDot(current: Boolean, passed: Boolean) {
    val mint = AppColors.primary
    val track = AppColors.track
    val ink = inkOn()
    Canvas(Modifier.size(24.dp)) {
        val r = size.minDimension / 2
        when {
            passed -> {
                drawCircle(mint, r)
                val p = Path().apply { moveTo(size.width * 0.28f, size.height * 0.52f); lineTo(size.width * 0.44f, size.height * 0.68f); lineTo(size.width * 0.74f, size.height * 0.34f) }
                drawPath(p, ink, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            current -> { drawCircle(mint, r - 1.5.dp.toPx(), style = Stroke(3.dp.toPx())); drawCircle(mint, r * 0.45f) }
            else -> drawCircle(track, r - 1.dp.toPx(), style = Stroke(2.5.dp.toPx()))
        }
    }
}

// ------------------------------------------------------------------------------------------------ all weeks

@Composable
private fun AllWeeksCard(plan: RacePlan, week: PlanWeek?, openInitially: Boolean) {
    var open by remember { mutableStateOf(openInitially) }
    SectionCard(
        Modifier.testTag("weeks"),
        title = "All ${plan.weeks.size} weeks",
        onClick = { open = !open },
        action = { Pill(if (open) "Hide" else "Show", color = AppColors.textMuted) },
    ) {
        if (!open) {
            Text(
                "Dates, phase, planned minutes and the key session for every week. Tap to open.",
                style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                plan.weeks.forEach { w ->
                    val current = week != null && w.index == week.index
                    Row(
                        Modifier.fillMaxWidth().clip(AppShapes.small)
                            .background(if (current) AppColors.primary.copy(alpha = 0.14f) else AppColors.surfaceRaised)
                            .padding(Spacing.md),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        Column(Modifier.width(76.dp)) {
                            Text("WEEK ${w.index}", style = AppType.eyebrow, color = if (current) AppColors.primary else AppColors.textMuted)
                            Text(RacePlanFormat.weekRange(w), style = MaterialTheme.typography.labelMedium, color = AppColors.textPrimary)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "${w.label} · ${if (w.phase == RacePhase.RACE) "${w.plannedMinutes} min + race" else "${w.plannedMinutes} min"}",
                                style = MaterialTheme.typography.titleSmall, color = AppColors.textPrimary,
                            )
                            Text(RacePlanFormat.keySession(w), style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ settings

@Composable
private fun PausedCard(onTurnOn: () -> Unit) {
    SectionCard(title = "The plan is paused") {
        Text(
            "Nothing is lost: your settings are kept, and Home and Start have stopped suggesting plan sessions. " +
                "Turn it back on whenever you like; the plan simply picks up from today.",
            style = MaterialTheme.typography.bodyLarge, color = AppColors.textPrimary,
        )
        BigActionButton("Turn the plan on", onClick = onTurnOn)
    }
}

@Composable
private fun SettingsCard(cfg: RacePlanSettings, today: LocalDate, onSettings: ((RacePlanSettings) -> RacePlanSettings) -> Unit) {
    SectionCard(Modifier.testTag("settings"), title = "Plan settings") {
        BigNumberStepper(
            value = RacePlanFormat.dayMonth(cfg.raceDate), unit = cfg.raceDate.year.toString(),
            onMinus = { onSettings { it.copy(raceDate = it.raceDate.minusDays(1).coerceAtLeast(today.plusDays(1))) } },
            onPlus = { onSettings { it.copy(raceDate = it.raceDate.plusDays(1).coerceAtMost(today.plusYears(2))) } },
            label = "Race date, ${cfg.raceDate.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase)}",
        )
        if (cfg.raceDate != com.calum.traininglog.core.DEFAULT_RACE_DATE) {
            SecondaryButton("Back to 27 Feb 2027", { onSettings { it.copy(raceDate = com.calum.traininglog.core.DEFAULT_RACE_DATE) } }, Modifier.fillMaxWidth())
        }
        Text(
            "The 14 weeks count back from the race, so changing the date moves every phase. The plan is built on Monday to Sunday weeks.",
            style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
        )

        Text("SESSIONS PER WEEK", style = AppType.eyebrow, color = AppColors.textMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            RacePlanSettings.SESSIONS_RANGE.forEach { n ->
                SelectChip("$n", cfg.sessionsPerWeek == n, { onSettings { it.copy(sessionsPerWeek = n) } })
            }
        }
        Text(
            "Fewer sessions drop the easy extras first. The key session and the two strength sessions always stay.",
            style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
        )

        BigNumberStepper(
            value = cfg.maxWeeklyMinutes?.toString() ?: "None", unit = if (cfg.maxWeeklyMinutes != null) "min" else "",
            onMinus = { onSettings { it.copy(maxWeeklyMinutes = RacePlanFormat.capDown(it.maxWeeklyMinutes)) } },
            onPlus = { onSettings { it.copy(maxWeeklyMinutes = RacePlanFormat.capUp(it.maxWeeklyMinutes)) } },
            label = "Weekly time cap",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            SelectChip("No limit", cfg.maxWeeklyMinutes == null, { onSettings { it.copy(maxWeeklyMinutes = null) } })
            Text(
                cfg.maxWeeklyMinutes?.let { "${RacePlanFormat.capHours(it)} a week at most" } ?: "The plan sets its own volume",
                style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
            )
        }
        Text(
            "With a cap, every week is scaled to fit and keeps its shape: lighter weeks, a short taper. Steps of 15 minutes.",
            style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
        )

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Column(Modifier.weight(1f)) {
                Text("Pause the plan", style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary)
                Text("Kept as it is; Home and Start stop suggesting sessions.", style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
            }
            Switch(
                checked = !cfg.enabled, onCheckedChange = { paused -> onSettings { it.copy(enabled = !paused) } },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = AppColors.primary, checkedThumbColor = AppColors.onPrimary,
                    uncheckedTrackColor = AppColors.surfaceHigh, uncheckedThumbColor = AppColors.textMuted,
                    uncheckedBorderColor = AppColors.outline,
                ),
            )
        }
    }
}

// ------------------------------------------------------------------------------------------------ evidence

@Composable
private fun EvidenceCard() {
    SectionCard(Modifier.testTag("evidence"), title = "What this plan rests on") {
        Text(
            "The ideas, and how strong the evidence is. Full notes are in docs/hyrox-plan-evidence.md.",
            style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
        )
        EvidenceLine("Mostly easy, about 20% hard", "Moderate", "Modest VO2peak gain, no clear race-performance edge. A safe default, not magic.", AppColors.info)
        EvidenceLine("Taper for about 2 weeks", "Strong", "Volume down 41-60% with intensity kept gave about 3% in a 27-study review.", AppColors.primary)
        EvidenceLine("Running is the biggest share of Hyrox time", "Weak", "One small study of 11 people. A hint, so the stations are trained too.", AppColors.energy)
        EvidenceLine("Strength on separate days from hard endurance", "Moderate", "Max strength is safe either way; explosive strength is better kept apart. A cheap precaution.", AppColors.info)
        EvidenceLine("Knee return to running is graded", "Clinical consensus", "From physio practice and a clinical review, not trials. The thresholds are this app's own sensible rules.", AppColors.reward)
        Text(
            "Not medical advice; if knee pain persists, see your physio.",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = AppColors.textPrimary,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EvidenceLine(idea: String, strength: String, note: String, color: Color) {
    Column(
        Modifier.fillMaxWidth().clip(AppShapes.small).background(AppColors.surfaceRaised).padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(idea, style = MaterialTheme.typography.titleSmall, color = AppColors.textPrimary)
        Pill("Evidence: $strength", color = color)
        Text(note, style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
    }
}

@Preview(widthDp = 411, heightDp = 2600)
@Composable
private fun RacePlanPreview() {
    TrainingTheme {
        RacePlanContent(emptyList(), Profile(), LocalDate.of(2026, 11, 19), {}, {}, {})
    }
}
