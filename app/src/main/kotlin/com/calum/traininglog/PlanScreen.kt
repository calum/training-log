package com.calum.traininglog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.calum.traininglog.core.Forecast
import com.calum.traininglog.core.HeadlineTarget
import com.calum.traininglog.core.Projection
import com.calum.traininglog.core.Scenario
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.UserSettings
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.AppShapes
import com.calum.traininglog.ui.AppType
import com.calum.traininglog.ui.BigNumberStepper
import com.calum.traininglog.ui.LineChart
import com.calum.traininglog.ui.Pill
import com.calum.traininglog.ui.SecondaryButton
import com.calum.traininglog.ui.SectionCard
import com.calum.traininglog.ui.SelectChip
import com.calum.traininglog.ui.Series
import com.calum.traininglog.ui.Spacing
import com.calum.traininglog.ui.StatTile
import com.calum.traininglog.ui.TrainingTheme
import java.time.LocalDate

private val liftChoices = listOf("squat" to "Squat", "deadlift" to "Deadlift", "rdl" to "RDL", "calf" to "Calf")
private const val HORIZON = 12

@Composable
fun PlanScreen(vm: AppViewModel) {
    val data by vm.data.collectAsState()
    PlanContent(
        records = data.records,
        headlineId = data.profile.headlineExerciseId,
        headlineKg = data.profile.headlineKg,
        onHeadline = { id, kg -> vm.updateProfile { it.copy(headlineExerciseId = id, headlineKg = kg) } },
        onBack = { vm.home() },
        today = LocalDate.now(),
        settings = data.profile.settings,
    )
}

@Composable
internal fun PlanContent(
    records: List<SessionRecord>,
    headlineId: String,
    headlineKg: Double,
    onHeadline: (String, Double) -> Unit,
    onBack: () -> Unit,
    today: LocalDate,
    settings: UserSettings = UserSettings(),
) {
    var gym by remember { mutableIntStateOf(settings.gymTarget) }
    var tread by remember { mutableIntStateOf(settings.treadmillTarget) }
    var lift by remember { mutableStateOf("squat") }
    val forecast = remember(records, today, gym, tread, headlineId, headlineKg, settings) {
        Projection.forecast(records, today, HORIZON, gym, tread, HeadlineTarget(headlineId, headlineKg), settings)
    }
    // This week's outlook always assumes the standard good week (2 gym, 3 treadmill).
    val outlook = remember(records, today, settings) {
        Projection.forecast(records, today, HORIZON, settings = settings).weekOutlook
    }

    Column(
        Modifier.fillMaxSize().background(AppColors.background).statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text("Your next 12 weeks", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, color = AppColors.textPrimary)
            SecondaryButton("Back", onBack)
        }

        SectionCard(title = "If you hit this week's target") {
            Text(outlook.summary, style = MaterialTheme.typography.bodyLarge, color = AppColors.textPrimary)
            outlook.liftLines.forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted)
            }
            if (records.isEmpty()) {
                Text(
                    "Starting from your start weights. Log a few sessions and this sharpens up.",
                    style = MaterialTheme.typography.bodyMedium, color = AppColors.info,
                )
            }
        }

        LiftCard(forecast, lift) { lift = it }
        HeadlineCard(forecast, headlineId, headlineKg, onHeadline)
        TreadmillCard(forecast)
        MilestonesCard(forecast)
        WhatIfCard(gym, tread, { gym = it }, { tread = it })

        Text(
            "All of this is an estimate, not a promise. Real weeks wobble, and that is fine.",
            style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
        )
        Spacer(Modifier.height(Spacing.xl))
    }
}

@Composable
private fun LiftCard(f: Forecast, selected: String, onPick: (String) -> Unit) {
    SectionCard(title = "Lift forecast (estimates)") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            liftChoices.forEach { (id, name) -> SelectChip(name, selected == id, { onPick(id) }) }
        }
        val lf = f.lift(selected) ?: return@SectionCard
        val cons = lf.scenarios.getValue(Scenario.CONSERVATIVE).weeks
        val exp = lf.scenarios.getValue(Scenario.EXPECTED).weeks
        val best = lf.scenarios.getValue(Scenario.BEST).weeks
        LineChart(
            series = listOf(
                Series("Conservative", cons.map { it.weightKg.toFloat() }, AppColors.info, dashed = true),
                Series("Expected", exp.map { it.weightKg.toFloat() }, AppColors.primary),
                Series("Best", best.map { it.weightKg.toFloat() }, AppColors.reward, dashed = true),
            ),
            xLabels = exp.map { if (it.deload) "${it.weekOffset}*" else "${it.weekOffset}" },
            yFormat = { fmt(it.toDouble()) },
        )
        Text(
            "Weeks from now; * is a planned lighter week. Conservative assumes about half of sessions earn an increase, expected about 70%, best every one.",
            style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
        )
        if (lf.restartApplied) {
            Text(
                "After the break you restart at about 90%, so the first weeks come back quickly.",
                style = MaterialTheme.typography.bodyMedium, color = AppColors.info,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            listOf(4, 8, 12).forEach { w ->
                val e = exp.firstOrNull { it.weekOffset == w } ?: return@forEach
                val lo = cons.first { it.weekOffset == w }.weightKg
                val hi = best.first { it.weekOffset == w }.weightKg
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    StatTile("Week $w", fmt(e.weightKg), Modifier.fillMaxWidth(), unit = "kg")
                    Text(
                        "${fmt(lo)} to ${fmt(hi)} kg",
                        style = MaterialTheme.typography.labelMedium, color = AppColors.textMuted,
                        modifier = Modifier.padding(horizontal = Spacing.sm),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeadlineCard(f: Forecast, id: String, target: Double, onChange: (String, Double) -> Unit) {
    SectionCard(title = "Headline target") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            liftChoices.forEach { (lid, name) ->
                SelectChip(name, id == lid, { onChange(lid, target) }, accent = AppColors.reward)
            }
        }
        BigNumberStepper(
            value = fmt(target), unit = "kg",
            onMinus = { onChange(id, (target - 2.5).coerceAtLeast(20.0)) },
            onPlus = { onChange(id, (target + 2.5).coerceAtMost(500.0)) },
            label = "Target",
        )
        val h = f.headline ?: return@SectionCard
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            listOf(
                Triple(Scenario.CONSERVATIVE, "Steady", AppColors.info),
                Triple(Scenario.EXPECTED, "Expected", AppColors.primary),
                Triple(Scenario.BEST, "Best", AppColors.reward),
            ).forEach { (s, name, color) ->
                val w = h.weeksToTarget[s]
                StatTile(
                    name,
                    when { w == null -> "12+"; w == 0 -> "now"; else -> "~$w" },
                    Modifier.weight(1f),
                    unit = if (w == null || w > 0) "wks" else "",
                    accent = color,
                )
            }
        }
        val e = h.weeksToTarget[Scenario.EXPECTED]
        Text(
            when (e) {
                0 -> "You are already there. Nice."
                null -> "This target is beyond the 12 week window at the expected pace; it is still a sensible one to build towards."
                else -> "At the expected pace that is about $e weeks away."
            },
            style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
        )
    }
}

@Composable
private fun TreadmillCard(f: Forecast) {
    val t = f.treadmill
    SectionCard(title = "Treadmill") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            StatTile("Per week", "${t.weeklyMinutes}", Modifier.weight(1f), unit = "min", accent = AppColors.info)
            StatTile("By week 12", "${t.weeks.lastOrNull()?.cumulativeMinutes ?: 0}", Modifier.weight(1f), unit = "min", accent = AppColors.energy)
        }
        LineChart(
            series = listOf(Series("Total minutes", listOf(0f) + t.weeks.map { it.cumulativeMinutes.toFloat() }, AppColors.energy)),
            xLabels = listOf("0") + t.weeks.map { "${it.weekOffset}" },
            height = 160.dp,
        )
        Text(
            "${t.sessionsPerWeek} sessions of about ${t.minutesPerSession} min" +
                if (t.basedOnSessions > 0) ", based on your last ${t.basedOnSessions}." else " (a starting guess).",
            style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
        )
    }
}

@Composable
private fun MilestonesCard(f: Forecast) {
    SectionCard(title = "Milestones") {
        f.milestones.forEach { m ->
            Row(
                Modifier.fillMaxWidth().background(AppColors.surfaceRaised, AppShapes.medium).padding(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Pill("Wk ${m.weekOffset}", color = AppColors.reward, filled = true)
                Text(
                    m.text.substringAfter(": ").replaceFirstChar { it.uppercase() },
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = AppColors.textPrimary,
                )
            }
        }
    }
}

@Composable
private fun WhatIfCard(gym: Int, tread: Int, onGym: (Int) -> Unit, onTread: (Int) -> Unit) {
    SectionCard(title = "What if...") {
        Text("GYM PACE", style = AppType.eyebrow, color = AppColors.textMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            (listOf(1, 2, 3) + gym).distinct().sorted().forEach { n -> SelectChip("$n gym a week", gym == n, { onGym(n) }) }
        }
        Text("TREADMILL PACE", style = AppType.eyebrow, color = AppColors.textMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            (listOf(2, 3, 4) + tread).distinct().sorted().forEach { n -> SelectChip("$n treadmill", tread == n, { onTread(n) }, accent = AppColors.energy) }
        }
        Text(
            "Everything above updates as you switch. 2 gym and 3 treadmill is the default good week.",
            style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
        )
    }
}

@Preview(showBackground = true, widthDp = 400, heightDp = 1800)
@Composable
private fun PlanPreview() {
    TrainingTheme { PlanContent(emptyList(), "squat", 100.0, { _, _ -> }, {}, LocalDate.of(2026, 10, 30)) }
}
