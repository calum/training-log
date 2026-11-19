package com.calum.traininglog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.HealthLevel
import com.calum.traininglog.core.MoveOption
import com.calum.traininglog.core.PlanHealth
import com.calum.traininglog.core.PlannedSession
import com.calum.traininglog.core.Profile
import com.calum.traininglog.core.RacePlan
import com.calum.traininglog.core.RacePlanner
import com.calum.traininglog.core.ReplanExport
import com.calum.traininglog.core.RestDays
import com.calum.traininglog.core.SessionKind
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.SkipReason
import com.calum.traininglog.core.TimeSuggestion
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.AppShapes
import com.calum.traininglog.ui.AppType
import com.calum.traininglog.ui.BigActionButton
import com.calum.traininglog.ui.HeroCard
import com.calum.traininglog.ui.Pill
import com.calum.traininglog.ui.SecondaryButton
import com.calum.traininglog.ui.SectionCard
import com.calum.traininglog.ui.SelectChip
import com.calum.traininglog.ui.Spacing
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/*
 * Flexibility UI for the race plan: move and skip a session, 'I have some time', the replan card and the rest-day
 * chips. All wording is kind and never red (see docs/design-system.md): skipping is normal, nothing is a failure.
 */

internal fun copyToClipboard(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

internal fun shareText(ctx: Context, title: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun shortDayName(d: DayOfWeek) = d.getDisplayName(TextStyle.SHORT, Locale.UK)

// ------------------------------------------------------------------------------------------------ small pieces

/** A day (or any short two-line label) to pick: [top] big, [bottom] small. Disabled days stay visible, quietly. */
@Composable
internal fun DayPickChip(
    top: String, bottom: String?, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    accent: Color = AppColors.primary,
) {
    val haptic = LocalHapticFeedback.current
    val bg = when {
        selected -> accent
        enabled -> AppColors.surfaceHigh
        else -> AppColors.surface
    }
    val fg = when {
        selected -> AppColors.onPrimary
        enabled -> AppColors.textPrimary
        else -> AppColors.textMuted.copy(alpha = 0.5f)
    }
    Column(
        modifier.heightIn(min = 56.dp).clip(AppShapes.small).background(bg)
            .clickable(enabled = enabled, role = Role.RadioButton) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
            .padding(vertical = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(top, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
        if (bottom != null) Text(bottom, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
    }
}

/** A calm line saying what just changed, with Undo. */
@Composable
internal fun ChangeBanner(text: String, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(AppShapes.small).background(AppColors.info.copy(alpha = 0.14f)).padding(start = Spacing.md, top = Spacing.xs, bottom = Spacing.xs, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = AppColors.textPrimary)
        TextButton(onClick = onUndo, Modifier.testTag("undo-change")) { Text("Undo", color = AppColors.info) }
    }
}

/** The new profile and the plain 'what changed' line for skipping [s] with [reason]. */
internal fun skipWithMessage(profile: Profile, records: List<SessionRecord>, today: LocalDate, s: PlannedSession, reason: SkipReason): Pair<Profile, String> {
    val np = RacePlanner.skip(profile, s, reason, today)
    val after = RacePlanner.build(profile.settings.racePlan, records, today, np)
    return np to "Skipped. " + RacePlanner.skipOutcome(after, s)
}

// ------------------------------------------------------------------------------------------------ move and skip panels

/** Day chips for the session's week (rest days marked, gentle hints), then a confirm. */
@Composable
internal fun MovePanel(options: List<MoveOption>, onConfirm: (LocalDate) -> Unit, onCancel: () -> Unit) {
    var picked by remember { mutableStateOf<LocalDate?>(null) }
    val sel = options.firstOrNull { it.date == picked }
    Column(
        Modifier.fillMaxWidth().testTag("move-panel").clip(AppShapes.medium).background(AppColors.surfaceHigh).padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text("MOVE TO", style = AppType.eyebrow, color = AppColors.textMuted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { o ->
                DayPickChip(
                    shortDayName(o.date.dayOfWeek),
                    when { o.isCurrent -> "now"; o.isRestDay -> "rest"; else -> o.date.dayOfMonth.toString() },
                    selected = o.date == picked, enabled = o.allowed, onClick = { picked = o.date },
                    Modifier.weight(1f).testTag("day-${o.date.dayOfWeek.name.take(3).lowercase()}"),
                    accent = if (o.isRestDay) AppColors.info else AppColors.primary,
                )
            }
        }
        val note = when {
            sel == null -> "Rest days are marked. Pick any day that is still ahead; moving onto a rest day just makes it a one-off training day."
            sel.hint != null -> sel.hint!!
            else -> "That works well."
        }
        Text(note, style = MaterialTheme.typography.bodyMedium, color = if (sel?.hint != null) AppColors.info else AppColors.textMuted)
        BigActionButton(
            if (sel != null) "Move to ${PlanDay.name(sel.date)}" else "Pick a day",
            onClick = { sel?.let { onConfirm(it.date) } }, enabled = sel != null, modifier = Modifier.fillMaxWidth().testTag("confirm-move"),
        )
        TextButton(onClick = onCancel, Modifier.fillMaxWidth()) { Text("Cancel", color = AppColors.textMuted) }
    }
}

/** Reason chips and a kind confirmation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SkipPanel(onConfirm: (SkipReason) -> Unit, onCancel: () -> Unit, title: String = "SKIP THIS SESSION") {
    var reason by remember { mutableStateOf<SkipReason?>(null) }
    Column(
        Modifier.fillMaxWidth().testTag("skip-panel").clip(AppShapes.medium).background(AppColors.surfaceHigh).padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(title, style = AppType.eyebrow, color = AppColors.textMuted)
        Text(
            "That is completely fine, kids and life come first. Pick a reason and the plan will adjust. Nothing is carried over or doubled.",
            style = MaterialTheme.typography.bodyMedium, color = AppColors.textPrimary,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SkipReason.entries.forEach { r -> SelectChip(r.label, reason == r, { reason = r }, Modifier.testTag("reason-${r.name.lowercase()}")) }
        }
        BigActionButton("Skip it", onClick = { reason?.let(onConfirm) }, enabled = reason != null, modifier = Modifier.fillMaxWidth().testTag("confirm-skip"))
        TextButton(onClick = onCancel, Modifier.fillMaxWidth()) { Text("Cancel", color = AppColors.textMuted) }
    }
}

internal object PlanDay {
    fun name(d: LocalDate): String = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK)
}

// ------------------------------------------------------------------------------------------------ I have some time

private val TIME_CHIPS = listOf(20, 30, 45, 60, 90)
private val FEELINGS = listOf(Feeling.GOOD to "Good", Feeling.OKAY to "Okay", Feeling.ROUGH to "Rough", Feeling.ILL to "Unwell")

private enum class TodayPanel { NONE, TIME, SKIP }

/**
 * The two buttons for today: 'I have some time' (minutes, then a suggestion with Start) and 'Skip today' (reasons).
 * Shown while the plan is running; 'Skip today' only when a session is planned for today and still open.
 */
@Composable
internal fun TodayActions(
    plan: RacePlan, records: List<SessionRecord>, profile: Profile, today: LocalDate,
    onStart: (PlannedSession) -> Unit, onProfile: ((Profile) -> Profile) -> Unit, modifier: Modifier = Modifier,
) {
    if (plan.segmentOf(today) == null) return
    var panel by remember { mutableStateOf(TodayPanel.NONE) }
    var minutes by remember { mutableStateOf<Int?>(null) }
    var feeling by remember { mutableStateOf(Feeling.OKAY) }
    var banner by remember { mutableStateOf<Pair<String, PlannedSession>?>(null) }
    val todays = RacePlanFormat.todaysOpenSession(plan, records, today)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SecondaryButton(
                "I have some time", { panel = if (panel == TodayPanel.TIME) TodayPanel.NONE else TodayPanel.TIME },
                Modifier.weight(1.5f).testTag("some-time"), accent = AppColors.primary,
            )
            if (todays != null) SecondaryButton(
                "Skip today", { panel = if (panel == TodayPanel.SKIP) TodayPanel.NONE else TodayPanel.SKIP },
                Modifier.weight(1f).testTag("skip-today"),
            )
        }
        banner?.let { (text, skipped) ->
            ChangeBanner(text, onUndo = { onProfile { RacePlanner.undo(it, skipped) }; banner = null })
        }
        when (panel) {
            TodayPanel.NONE -> {}
            TodayPanel.TIME -> Column(
                Modifier.fillMaxWidth().testTag("time-panel").clip(AppShapes.medium).background(AppColors.surfaceHigh).padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text("HOW MUCH TIME DO YOU HAVE?", style = AppType.eyebrow, color = AppColors.textMuted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TIME_CHIPS.forEach { m ->
                        DayPickChip("$m", "min", minutes == m, true, { minutes = m }, Modifier.weight(1f).testTag("minutes-$m"))
                    }
                }
                Text("HOW DO YOU FEEL?", style = AppType.eyebrow, color = AppColors.textMuted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FEELINGS.forEach { (f, label) ->
                        DayPickChip(label, null, feeling == f, true, { feeling = f }, Modifier.weight(1f).testTag("feeling-${f.name.lowercase()}"))
                    }
                }
                val m = minutes
                if (m != null) {
                    val s = remember(m, feeling, plan, records, profile, today) {
                        RacePlanner.suggestForTime(plan, records, today, m, feeling, null, profile)
                    }
                    TimeSuggestionCard(s, onStart = { panel = TodayPanel.NONE; onStart(it) }, onRest = { panel = TodayPanel.NONE })
                }
            }
            TodayPanel.SKIP -> if (todays != null) SkipPanel(
                onConfirm = { reason ->
                    val (np, msg) = skipWithMessage(profile, records, today, todays, reason)
                    onProfile { np }
                    banner = msg to todays.copy(skipReason = reason)
                    panel = TodayPanel.NONE
                },
                onCancel = { panel = TodayPanel.NONE }, title = "SKIP TODAY",
            )
        }
    }
}

/** A [TimeSuggestion] with its reason, a Start button and the alternative. [hero] puts it on the gradient card (Start screen). */
@Composable
internal fun TimeSuggestionCard(s: TimeSuggestion, onStart: (PlannedSession) -> Unit, onRest: () -> Unit, hero: Boolean = false) {
    val fg = if (hero) AppColors.onHero else AppColors.textPrimary
    val muted = if (hero) AppColors.onHero.copy(alpha = 0.9f) else AppColors.textMuted
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().testTag("time-suggestion"), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Pill(
                when { s.isRest -> "Rest"; s.extra -> "Bonus, optional"; else -> "Suggested" },
                color = if (hero) AppColors.onHero else if (s.extra) AppColors.reward else AppColors.primary, filled = false,
            )
            Text(s.primary.title, style = MaterialTheme.typography.titleLarge, color = fg)
            if (!s.isRest) {
                Text(
                    "${s.primary.minutes} min · ${RacePlanFormat.intensityLabel(s.primary.intensity)} · ${s.primary.rpeLabel}",
                    style = MaterialTheme.typography.bodyMedium, color = muted,
                )
            }
            Text(s.reason, style = MaterialTheme.typography.bodyMedium, color = fg)
            BigActionButton(if (s.isRest) "OK, rest" else "Start", onClick = { if (s.isRest) onRest() else onStart(s.primary) }, modifier = Modifier.testTag("suggestion-start"))
            s.alternative?.let { a ->
                SecondaryButton("Instead: ${a.title}${if (" min" in a.title) "" else ", ${a.minutes} min"}", { onStart(a) }, Modifier.fillMaxWidth().testTag("suggestion-alt"))
            }
        }
    }
    if (hero) HeroCard { body() } else Box(Modifier.fillMaxWidth().clip(AppShapes.medium).background(AppColors.surfaceRaised).padding(Spacing.md)) { body() }
}

// ------------------------------------------------------------------------------------------------ replan

/** Shown when the plan suggests a fresh start: calm copy, the reasons, copy and share for Claude, and 'Not now'. */
@Composable
internal fun ReplanCard(
    health: PlanHealth, plan: RacePlan, records: List<SessionRecord>, profile: Profile, today: LocalDate,
    onProfile: ((Profile) -> Profile) -> Unit, modifier: Modifier = Modifier,
) {
    if (health.level != HealthLevel.SUGGEST) return
    val ctx = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    SectionCard(modifier.testTag("replan"), title = "A fresh start?") {
        Text(health.summary, style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary)
        health.reasons.forEach {
            Text("· $it", style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted)
        }
        health.physioNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.info) }
        Text(
            "Copy a summary for Claude, paste it into a chat, and ask for a new plan. Then change the settings it suggests.",
            style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
        )
        BigActionButton(
            if (copied) "Copied, now paste it to Claude" else "Copy summary for Claude",
            onClick = { copyToClipboard(ctx, "Re-plan summary", ReplanExport.text(plan, records, profile, today)); copied = true },
            modifier = Modifier.testTag("replan-copy"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SecondaryButton("Share", { shareText(ctx, "Share summary", ReplanExport.text(plan, records, profile, today)) }, Modifier.weight(1f).testTag("replan-share"))
            SecondaryButton(
                "Not now", { onProfile { it.copy(planAdjustments = it.planAdjustments.dismissReplan(today)) } },
                Modifier.weight(1f).testTag("replan-dismiss"),
            )
        }
    }
}

// ------------------------------------------------------------------------------------------------ rest-day settings

/** Mon-Sun chips for the default rest weekdays (selected = a rest day every week). */
@Composable
internal fun RestWeekdayChips(profile: Profile, onProfile: ((Profile) -> Profile) -> Unit) {
    Text("REST DAYS EVERY WEEK", style = AppType.eyebrow, color = AppColors.textMuted)
    Row(Modifier.fillMaxWidth().testTag("rest-weekdays"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DayOfWeek.entries.forEach { d ->
            val on = d in profile.restWeekdays
            DayPickChip(
                shortDayName(d), if (on) "rest" else null, on, true, { onProfile { RestDays.toggleWeekday(it, d) } },
                Modifier.weight(1f).testTag("restday-${d.name.take(3).lowercase()}"), accent = AppColors.info,
            )
        }
    }
    Text(
        "The plan schedules around these. Tap a day on Home for a one-off change, or move a single session from the plan.",
        style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
    )
}

/** The quiet line for a mild patch (no alert, no buttons). */
@Composable
internal fun QuietLine(health: PlanHealth) {
    if (health.level != HealthLevel.GENTLE) return
    Text(health.summary, Modifier.fillMaxWidth().testTag("quiet-line"), style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted, textAlign = TextAlign.Start)
}

private fun kindWord(k: SessionKind) = when (k) {
    SessionKind.STRENGTH -> "Lift"; SessionKind.KEY -> "Hard"; SessionKind.SIM -> "Sim"
    SessionKind.LONG_EASY -> "Long"; SessionKind.EASY -> "Easy"; SessionKind.REST -> "rest"
}

/** The week at a glance: a cell per day with what is planned, 'rest' for rest days, 'skip' for skipped sessions. */
@Composable
internal fun WeekStrip(week: com.calum.traininglog.core.PlanWeek, profile: Profile, today: LocalDate, done: Set<PlannedSession>) {
    val days = generateSequence(week.start) { it.plusDays(1) }.takeWhile { it <= week.end }.toList()
    Row(Modifier.fillMaxWidth().testTag("week-strip"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        days.forEach { d ->
            val ss = week.sessions.filter { it.date == d }
            val live = ss.filter { !it.isSkipped }
            val isRest = live.isEmpty() && RestDays.isRestDay(profile, d)
            val label = when {
                live.size >= 2 -> "Two"
                live.size == 1 -> kindWord(live[0].kind)
                ss.isNotEmpty() -> "skip"
                isRest -> "rest"
                else -> "free"
            }
            val color = when {
                live.isNotEmpty() && live.all { it in done } -> AppColors.primary
                live.isNotEmpty() -> AppColors.textPrimary
                isRest -> AppColors.info
                else -> AppColors.textMuted
            }
            Column(
                Modifier.weight(1f).clip(AppShapes.small).background(if (d == today) AppColors.primary.copy(alpha = 0.18f) else AppColors.surfaceHigh).padding(vertical = Spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(shortDayName(d.dayOfWeek), style = MaterialTheme.typography.labelMedium, color = if (d == today) AppColors.primary else AppColors.textMuted, maxLines = 1)
                Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
            }
        }
    }
}
