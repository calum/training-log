package com.calum.traininglog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.calum.traininglog.core.Feeling
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
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StartScreen(vm: AppViewModel) {
    val inputs by vm.inputs.collectAsState()
    val rec by vm.recommendation.collectAsState()
    val data by vm.data.collectAsState()
    val restDay = RestDays.isRestDay(data.profile, LocalDate.now())
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

        FieldLabel("HOW MUCH TIME?")
        ChipFlow(listOf(20, 30, 45, 60, 90, 120), inputs.minutes, { if (it == 120) "2 hours" else "$it min" }) { m ->
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            (0..10).forEach { n -> SelectChip(n.toString(), inputs.knee == n, { vm.setInputs { it.copy(knee = n) } }) }
            SelectChip("Skip", inputs.knee == null, { vm.setInputs { it.copy(knee = null) } }, accent = AppColors.info)
        }

        SelectChip(
            if (inputs.atGym) "At the gym ✓" else "I'm already at the gym",
            inputs.atGym, { vm.setInputs { it.copy(atGym = !it.atGym) } },
        )

        BigActionButton("What should I do?", onClick = { vm.recommend() })

        rec?.let { r ->
            HeroCard {
                Pill("Suggested", color = AppColors.onHero, filled = false)
                Text(
                    r.primary.label, color = AppColors.onHero, style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
                Text(
                    r.reason, color = AppColors.onHero.copy(alpha = 0.85f),
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
