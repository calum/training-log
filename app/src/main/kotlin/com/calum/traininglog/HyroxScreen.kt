package com.calum.traininglog

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.calum.traininglog.core.HyroxDivision
import com.calum.traininglog.core.HyroxGear
import com.calum.traininglog.core.HyroxPlace
import com.calum.traininglog.core.HyroxRuns
import com.calum.traininglog.core.HyroxGuide
import com.calum.traininglog.core.HyroxLadder
import com.calum.traininglog.core.HyroxProgression
import com.calum.traininglog.core.HyroxStandards
import com.calum.traininglog.core.HyroxStation
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.UserSettings
import com.calum.traininglog.ui.AppColors
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

@Composable
fun HyroxScreen(vm: AppViewModel) {
    val data by vm.data.collectAsState()
    HyroxHubContent(
        records = data.records,
        settings = data.profile.settings,
        onDivision = { d -> vm.updateProfile { it.copy(settings = it.settings.copy(hyroxDivision = d)) } },
        onSettings = { f -> vm.updateProfile { p -> p.copy(settings = f(p.settings).sanitized()) } },
        onOpen = { vm.go(Screen.HyroxStation(it)) },
        onStart = { vm.go(Screen.HyroxSession(emptySet())) },
        onLog = { vm.go(Screen.HyroxLog(null)) },
        onBack = { vm.home() },
    )
}

@Composable
internal fun HyroxHubContent(
    records: List<SessionRecord>,
    settings: UserSettings,
    onDivision: (HyroxDivision) -> Unit,
    onOpen: (HyroxStation) -> Unit,
    onStart: () -> Unit,
    onLog: () -> Unit,
    onBack: () -> Unit,
    onSettings: ((UserSettings) -> UserSettings) -> Unit = {},
) {
    val division = settings.hyroxDivision
    val overrides = settings.hyroxWeightOverrides
    LazyColumn(
        Modifier.fillMaxSize().background(AppColors.background).statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text("Hyrox", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, color = AppColors.textPrimary)
                SecondaryButton("Back", onBack)
            }
        }
        item {
            HeroCard(brush = AppColors.gradientPrimary) {
                Text("HYROX IN 30 SECONDS", style = AppType.eyebrow, color = HeroInk.copy(alpha = 0.8f))
                Text(
                    "8 rounds of a 1 km run plus a station.",
                    style = MaterialTheme.typography.titleLarge, color = HeroInk,
                )
                Text(
                    "You already know the run and the row, so we start with the other six. Learn one at a time, light and easy.",
                    style = MaterialTheme.typography.bodyMedium, color = HeroInk.copy(alpha = 0.9f),
                )
            }
        }
        item { DivisionCard(division, overrides, onDivision) }
        item { EquipmentCard(settings, onSettings) }
        items(HyroxStation.entries, key = { it.id }) { st ->
            StationCard(st, records, settings, onClick = { onOpen(st) })
        }
        item {
            SectionCard {
                Text("A kind note on your knee", style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary)
                Text(
                    "Burpee broad jumps and sandbag lunges are the knee-heavy ones. Start light, build slowly, " +
                        "and skip or shorten them on a day the knee talks. Nothing is lost.",
                    style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                BigActionButton("Start a Hyrox session", onStart)
                SecondaryButton("Log a station", onLog, Modifier.fillMaxWidth())
            }
        }
        item { Spacer(Modifier.navigationBarsPadding().height(Spacing.xl)) }
    }
}

@Composable
private fun DivisionCard(division: HyroxDivision, overrides: Map<String, Double>, onDivision: (HyroxDivision) -> Unit) {
    SectionCard(title = "Your division") {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SelectChip("Men's Open", division == HyroxDivision.MEN_OPEN, { onDivision(HyroxDivision.MEN_OPEN) })
                SelectChip("Women's Open", division == HyroxDivision.WOMEN_OPEN, { onDivision(HyroxDivision.WOMEN_OPEN) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SelectChip("Men's Pro", division == HyroxDivision.MEN_PRO, { onDivision(HyroxDivision.MEN_PRO) })
                SelectChip("Women's Pro", division == HyroxDivision.WOMEN_PRO, { onDivision(HyroxDivision.WOMEN_PRO) })
            }
        }
        Text(HyroxFormat.divisionSummary(division, overrides), style = MaterialTheme.typography.bodyMedium, color = AppColors.textPrimary)
        val approx = HyroxFormat.approx(division)
        Text(
            (if (approx) "~ approx: some values are approximate. " else "") + HyroxStandards.standardsNote,
            style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
        )
    }
}

/** Which kit he has at the gym and at home (home sessions only use ticked kit), plus the treadmill run speed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EquipmentCard(settings: UserSettings, onSettings: ((UserSettings) -> UserSettings) -> Unit) {
    SectionCard(title = "My equipment") {
        Text(
            "Tick what you have. Home sessions only use ticked equipment.",
            style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
        )
        HyroxPlace.entries.forEach { place ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(if (place == HyroxPlace.GYM) "AT THE GYM" else "AT HOME", style = AppType.eyebrow, color = AppColors.textMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    val have = settings.hyroxGearAt(place)
                    HyroxGear.entries.forEach { g ->
                        SelectChip(g.label, g in have, { onSettings { it.withHyroxGear(place, g, g !in have) } })
                    }
                }
            }
        }
        Text("TREADMILL RUNS", style = AppType.eyebrow, color = AppColors.textMuted)
        BigNumberStepper(
            HyroxText.speed(settings.hyroxRunSpeedKmh), "km/h",
            { onSettings { it.copy(hyroxRunSpeedKmh = HyroxRuns.snapSpeed(it.hyroxRunSpeedKmh - HyroxRuns.SPEED_STEP)) } },
            { onSettings { it.copy(hyroxRunSpeedKmh = HyroxRuns.snapSpeed(it.hyroxRunSpeedKmh + HyroxRuns.SPEED_STEP)) } },
            label = "Run speed",
        )
        BigNumberStepper(
            HyroxText.incline(settings.hyroxRunInclinePct), "% incline",
            { onSettings { it.copy(hyroxRunInclinePct = (it.hyroxRunInclinePct - 0.5).coerceAtLeast(0.0)) } },
            { onSettings { it.copy(hyroxRunInclinePct = (it.hyroxRunInclinePct + 0.5).coerceAtMost(HyroxRuns.INCLINE_RANGE.endInclusive)) } },
            label = "Run incline",
        )
        Text(
            "Runs are optional and stay off unless you switch them on. With a treadmill ticked, each run counts down the time for its distance at this speed.",
            style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
        )
    }
}

@Composable
private fun StationCard(station: HyroxStation, records: List<SessionRecord>, settings: UserSettings, onClick: () -> Unit) {
    val entries = remember(records, station) { HyroxFormat.entries(records, station) }
    val next = remember(entries, settings) {
        HyroxProgression.next(station, settings.hyroxDivision, entries, settings.hyroxWeightOverrides)
    }
    val std = settings.hyroxStandard(station)
    val confident = HyroxGuide.of(station).confident
    SectionCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                "${station.order}",
                Modifier.clip(RoundedCornerShape(50)).background(AppColors.surfaceHigh).padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium, color = AppColors.textMuted,
            )
            Text(station.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary)
            if (station.kneeHeavy) Pill("Knee-heavy", color = AppColors.info)
        }
        Text(
            (if (std.approx) "~ " else "") + HyroxFormat.standard(station, std),
            style = MaterialTheme.typography.titleSmall, color = AppColors.primary,
        )
        Text(
            HyroxFormat.status(station, entries, next.level),
            style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
        )
        LevelBar(if (entries.isEmpty()) 0 else next.level)
        if (confident) Pill("You're confident here", color = AppColors.reward)
    }
}

/** Slim bar through the six ladder levels. */
@Composable
internal fun LevelBar(level: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 1..HyroxLadder.LEVELS) {
            Box(
                Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(50))
                    .background(if (i <= level) AppColors.primary else AppColors.track),
            )
        }
    }
}

@Preview(widthDp = 411, heightDp = 1800)
@Composable
private fun HubPreview() {
    TrainingTheme {
        HyroxHubContent(emptyList(), UserSettings(), {}, {}, {}, {}, {})
    }
}
