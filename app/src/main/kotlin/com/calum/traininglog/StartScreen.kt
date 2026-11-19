package com.calum.traininglog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.RacePlanner
import com.calum.traininglog.core.RestDays
import com.calum.traininglog.core.SessionType
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
import com.calum.traininglog.ui.TrainingTheme
import java.time.LocalDate

// ---------------- Start ----------------

/** Wrapping row of [SelectChip]s with consistent spacing. Shared by the logging screens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChipFlow(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        options.forEach { o -> SelectChip(label(o), o == selected, { onPick(o) }) }
    }
}

/** Equal-width chips laid out [perRow] to a row, so rows line up and nothing wraps awkwardly. */
@Composable
private fun <T> ChipGrid(options: List<T>, perRow: Int, isSelected: (T) -> Boolean, label: (T) -> String, onPick: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        options.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                row.forEach { o -> GridChip(label(o), isSelected(o), { onPick(o) }, Modifier.weight(1f)) }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Chip without side padding, so narrow cells ("10", "Skip") never clip. */
@Composable
private fun GridChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier.heightIn(min = Spacing.minTap).clip(AppShapes.chip)
            .background(if (selected) AppColors.primary else AppColors.surfaceRaised)
            .clickable(role = Role.RadioButton) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) AppColors.onPrimary else AppColors.textPrimary, maxLines = 1)
    }
}

/** Small section heading used above a group of choices. */
@Composable
internal fun FieldLabel(text: String) =
    Text(text, style = AppType.eyebrow, color = AppColors.textMuted, modifier = Modifier.padding(top = Spacing.xs))

/** Big friendly selectable tile (emoji + label). */
@Composable
private fun ChoiceTile(emoji: String, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg = if (selected) AppColors.primary else AppColors.surfaceRaised
    val fg = if (selected) AppColors.onPrimary else AppColors.textPrimary
    Column(
        modifier
            .heightIn(min = 88.dp)
            .clip(AppShapes.medium)
            .background(bg)
            .border(1.dp, if (selected) AppColors.primary else AppColors.outline.copy(alpha = 0.5f), AppShapes.medium)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = Spacing.md, horizontal = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(emoji, style = AppType.numberM)
        Text(label, color = fg, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

private fun feelingEmoji(f: Feeling) = when (f) {
    Feeling.GOOD -> "😄"
    Feeling.OKAY -> "🙂"
    Feeling.ROUGH -> "😮‍💨"
    Feeling.ILL -> "🤒"
}

private fun typeEmoji(t: SessionType) = when (t) {
    SessionType.GYM -> "🏋️"
    SessionType.TREADMILL -> "🏃"
    SessionType.CORE -> "🧘"
    SessionType.HIKE -> "🥾"
    SessionType.HYROX -> "🏁"
}

private val START_MINUTES = listOf(20, 30, 45, 60, 90, 120)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StartScreen(vm: AppViewModel) {
    val inputs by vm.inputs.collectAsState()
    val rec by vm.recommendation.collectAsState()
    val timeSuggestion by vm.timeSuggestion.collectAsState()
    val data by vm.data.collectAsState()
    val today = LocalToday.current ?: LocalDate.now()
    val restDay = RestDays.isRestDay(data.profile, today)
    val racePlan = remember(data.profile, data.records, today) {
        data.profile.settings.racePlan.takeIf { it.enabled }?.let { cfg -> RacePlanner.build(cfg, data.records, today, data.profile) }
    }
    val planned = racePlan?.let { RacePlanFormat.todaysOpenSession(it, data.records, today) }
    // Kick-off, today already logged: say plainly that nothing more is needed.
    val kickoffNote = if (planned == null) racePlan?.let { RacePlanFormat.kickoffTodayNote(it, today) } else null
    // Preselect the planned length (the nearest of the time chips) once, when the screen opens.
    LaunchedEffect(planned?.date, planned?.title) {
        planned?.let { p -> vm.setInputs { it.copy(minutes = START_MINUTES.minBy { m -> Math.abs(m - p.minutes) }) } }
    }
    Page {
        Title("Today")
        if (restDay) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Pill("Rest day", color = AppColors.info, filled = true)
                    Text("Rest day: only if you fancy it", color = AppColors.textPrimary, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        planned?.let { p ->
            SectionCard {
                Text("HYROX SIM", style = AppType.eyebrow, color = AppColors.energy)
                Text(
                    "From your race plan: ${p.title}", style = MaterialTheme.typography.titleLarge, color = AppColors.textPrimary,
                )
                Text(
                    "${p.minutes} min · ${RacePlanFormat.intensityLabel(p.intensity)} · ${p.rpeLabel}",
                    style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
                )
                Text(p.why, style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
                BigActionButton("Start the planned session", onClick = { vm.launchPlanned(p) })
                Text(
                    "Or work out something different below. The plan will be here either way.",
                    style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
                )
            }
        }

        kickoffNote?.let {
            SectionCard {
                Text("HYROX SIM · KICK-OFF", style = AppType.eyebrow, color = AppColors.energy)
                Text(it, style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary)
                Text(
                    "Anything else you fancy below is a bonus.",
                    style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
                )
            }
        }

        FieldLabel("HOW MUCH TIME?")
        ChipGrid(START_MINUTES, 3, { inputs.minutes == it }, { if (it == 120) "2 hours" else "$it min" }) { m ->
            vm.setInputs { it.copy(minutes = m) }
        }

        FieldLabel("HOW DO YOU FEEL?")
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Feeling.entries.forEach { f ->
                ChoiceTile(
                    feelingEmoji(f), f.name.lowercase().replaceFirstChar(Char::uppercase), inputs.feeling == f,
                    { vm.setInputs { it.copy(feeling = f) } }, Modifier.weight(1f),
                )
            }
        }

        FieldLabel("RIGHT KNEE, 0 (FINE) TO 10")
        // 0-5 on the first row, 6-10 and Skip on the second: six equal chips per row.
        ChipGrid((0..10).map<Int, Int?> { it } + null, 6, { inputs.knee == it }, { it?.toString() ?: "Skip" }) { n ->
            vm.setInputs { it.copy(knee = n) }
        }

        SelectChip(
            if (inputs.atGym) "At the gym ✓" else "I'm already at the gym",
            inputs.atGym, { vm.setInputs { it.copy(atGym = !it.atGym) } },
            modifier = Modifier.fillMaxWidth(),
        )

        BigActionButton("What should I do?", onClick = { vm.recommend() })

        timeSuggestion?.let { ts ->
            TimeSuggestionCard(ts, onStart = { vm.launchPlanned(it) }, onRest = { vm.home() }, hero = true)
        } ?: rec?.let { r ->
            HeroCard {
                Pill("Suggested", color = AppColors.onHero, filled = false)
                Text(
                    r.primary.label, color = AppColors.onHero, style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
                Text(
                    r.reason, color = AppColors.onHero,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.lg),
                )
                BigActionButton(
                    if (r.primary.type == null) "OK, rest" else "Let's go",
                    onClick = { vm.begin(r.primary.type, r.primary.minutes, r.primary.style) },
                )
                r.alternative?.let { a ->
                    SecondaryButton(
                        "Instead: ${a.label}",
                        onClick = { vm.begin(a.type, a.minutes, a.style) },
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.md),
                    )
                }
            }
        }

        FieldLabel("OR PICK IT YOURSELF")
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            for (t in SessionType.entries) {
                ChoiceTile(
                    typeEmoji(t), if (t == SessionType.HIKE) "Hike" else typeName(t), false,
                    { vm.begin(t) }, Modifier.weight(1f),
                )
            }
        }
        SecondaryButton("Back", onClick = { vm.home() }, modifier = Modifier.fillMaxWidth())
    }
}

@Preview(showBackground = true)
@Composable
private fun ChoiceTilePreview() {
    TrainingTheme {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceTile(feelingEmoji(Feeling.GOOD), "Good", true, {}, Modifier.weight(1f))
            ChoiceTile(feelingEmoji(Feeling.ILL), "Ill", false, {}, Modifier.weight(1f))
        }
    }
}
