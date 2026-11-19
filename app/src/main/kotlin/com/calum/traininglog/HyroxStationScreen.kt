package com.calum.traininglog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import com.calum.traininglog.core.HyroxGuide
import com.calum.traininglog.core.HyroxLadder
import com.calum.traininglog.core.HyroxProgression
import com.calum.traininglog.core.HyroxStandards
import com.calum.traininglog.core.ProgressStats
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.UserSettings
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.AppType
import com.calum.traininglog.ui.BigActionButton
import com.calum.traininglog.ui.BigNumberStepper
import com.calum.traininglog.ui.HeroCard
import com.calum.traininglog.ui.LineChart
import com.calum.traininglog.ui.Pill
import com.calum.traininglog.ui.SecondaryButton
import com.calum.traininglog.ui.SectionCard
import com.calum.traininglog.ui.Series
import com.calum.traininglog.ui.Spacing
import com.calum.traininglog.ui.TrainingTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import com.calum.traininglog.core.HyroxStation as Station

private val dayFmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

@Composable
fun HyroxStationScreen(vm: AppViewModel, station: Station) {
    val data by vm.data.collectAsState()
    HyroxStationContent(
        station = station,
        records = data.records,
        settings = data.profile.settings,
        today = LocalDate.now(),
        onRaceWeight = { kg ->
            vm.updateProfile { p ->
                val m = p.settings.hyroxWeightOverrides.toMutableMap()
                if (kg == null) m.remove(station.id) else m[station.id] = kg
                p.copy(settings = p.settings.copy(hyroxWeightOverrides = m))
            }
        },
        onPractice = { vm.go(Screen.HyroxSession(setOf(station))) },
        onLog = { vm.go(Screen.HyroxLog(station)) },
        onBack = { vm.go(Screen.Hyrox) },
    )
}

@Composable
internal fun HyroxStationContent(
    station: Station,
    records: List<SessionRecord>,
    settings: UserSettings,
    today: LocalDate,
    onRaceWeight: (Double?) -> Unit,
    onPractice: () -> Unit,
    onLog: () -> Unit,
    onBack: () -> Unit,
) {
    val division = settings.hyroxDivision
    val overrides = settings.hyroxWeightOverrides
    val guide = HyroxGuide.of(station)
    val std = settings.hyroxStandard(station)
    val entries = remember(records, station) { HyroxFormat.entries(records, station) }
    val next = remember(entries, settings) { HyroxProgression.next(station, division, entries, overrides) }
    val ladder = remember(settings, station) { HyroxLadder.of(division, station, overrides) }

    Column(
        Modifier.fillMaxSize().background(AppColors.background).statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(station.displayName, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, color = AppColors.textPrimary)
            SecondaryButton("Back", onBack)
        }

        HeroCard(brush = AppColors.gradientPrimary) {
            Text("STATION ${station.order} OF 8", style = AppType.eyebrow, color = AppColors.onHero.copy(alpha = 0.8f))
            Text(
                (if (std.approx) "~ " else "") + HyroxFormat.standard(station, std),
                style = MaterialTheme.typography.headlineSmall, color = AppColors.onHero,
            )
            if (std.note.isNotBlank()) Text(std.note, style = MaterialTheme.typography.bodySmall, color = AppColors.onHero.copy(alpha = 0.85f))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Pill("Level ${next.level} of ${HyroxLadder.LEVELS}", color = AppColors.onHero, filled = false)
                if (guide.confident) Pill("You're confident here", color = AppColors.onHero)
            }
        }

        if (guide.confident) {
            SectionCard(title = "You know this one") {
                Text(guide.summary, style = MaterialTheme.typography.bodyLarge, color = AppColors.textPrimary)
                Text("Pacing", style = MaterialTheme.typography.titleSmall, color = AppColors.primary)
                Text(guide.pacingTip, style = MaterialTheme.typography.bodyMedium, color = AppColors.textPrimary)
                Text(
                    "Race pace is a touch slower than your fresh 1 km, because you arrive after four stations and four runs.",
                    style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
                )
            }
        } else {
            SectionCard(title = "What it is") {
                Text(guide.whatItIs, style = MaterialTheme.typography.bodyLarge, color = AppColors.textPrimary)
                Text(guide.summary, style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted)
            }
        }

        SectionCard(title = "Technique") { Checklist(guide.tips, AppColors.primary) }

        if (!guide.confident) {
            SectionCard(title = "Common mistakes") { Checklist(guide.mistakes, AppColors.energy, dot = true) }
        }

        guide.kneeNote?.let { note ->
            SectionCard {
                Text("About your knee", style = MaterialTheme.typography.titleMedium, color = AppColors.info)
                Text(note, style = MaterialTheme.typography.bodyMedium, color = AppColors.textPrimary)
            }
        }

        if (!guide.confident) {
            SectionCard(title = "Pacing") {
                Text(guide.pacingTip, style = MaterialTheme.typography.bodyLarge, color = AppColors.textPrimary)
            }
            SectionCard(title = "No equipment? Train it with...") { Checklist(guide.alternatives, AppColors.info, dot = true) }
        }

        LadderCard(station, ladder, next.level, HyroxFormat.prescription(station, next), next.note)

        if (station.weighted) {
            RaceWeightCard(station, settings, std.weightKg ?: 0.0, onRaceWeight)
        }

        HistoryCard(station, entries, records, today)

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            BigActionButton("Practice this station", onPractice)
            SecondaryButton("Log it", onLog, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.navigationBarsPadding().height(Spacing.xl))
    }
}

@Composable
private fun Checklist(items: List<String>, accent: androidx.compose.ui.graphics.Color, dot: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        items.forEach { t ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Box(Modifier.padding(top = 8.dp).size(if (dot) 8.dp else 10.dp).clip(CircleShape).background(accent))
                Text(t, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = AppColors.textPrimary)
            }
        }
    }
}

@Composable
private fun LadderCard(
    station: Station,
    ladder: List<com.calum.traininglog.core.StationLevel>,
    current: Int,
    nextLine: String,
    nextNote: String,
) {
    SectionCard(title = "Your ladder") {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(AppColors.primary.copy(alpha = 0.12f)).padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text("NEXT SESSION", style = AppType.eyebrow, color = AppColors.primary)
            Text(nextLine, style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary)
            Text(nextNote, style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
        }
        Column {
            ladder.forEachIndexed { i, l ->
                val isCurrent = l.level == current
                val done = l.level < current
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Column(Modifier.width(28.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(28.dp).clip(CircleShape)
                                .background(if (isCurrent) AppColors.primary else if (done) AppColors.primary.copy(alpha = 0.35f) else AppColors.surfaceHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${l.level}", style = MaterialTheme.typography.labelMedium,
                                color = if (isCurrent) AppColors.onPrimary else AppColors.textPrimary,
                            )
                        }
                        if (i < ladder.lastIndex) {
                            Box(Modifier.width(2.dp).weight(1f).background(AppColors.track))
                        }
                    }
                    Column(Modifier.weight(1f).padding(bottom = Spacing.md), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                l.label, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall,
                                color = if (isCurrent) AppColors.primary else AppColors.textPrimary,
                            )
                            if (isCurrent) Pill("You are here", color = AppColors.primary, filled = true)
                            else if (l.level == HyroxLadder.RACE_LEVEL) Pill("Race", color = AppColors.reward)
                        }
                        Text(HyroxFormat.level(station, l), style = MaterialTheme.typography.bodyMedium, color = AppColors.textPrimary)
                        Text(l.targetNote, style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
                    }
                }
            }
        }
        Text("How you move up", style = MaterialTheme.typography.titleSmall, color = AppColors.textPrimary)
        Text(
            "Finish the work at effort 8/10 or easier, with decent technique, and the next session steps up a level. " +
                "A hard day just repeats the level. Nothing is ever a penalty.",
            style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
        )
    }
}

@Composable
private fun RaceWeightCard(station: Station, settings: UserSettings, current: Double, onChange: (Double?) -> Unit) {
    val base = HyroxStandards.of(settings.hyroxDivision, station).weightKg ?: current
    val edited = settings.hyroxWeightOverrides[station.id] != null
    fun set(kg: Double) { onChange(if (kg == base) null else kg.coerceIn(1.0, 300.0)) }
    SectionCard(title = "Race weight") {
        BigNumberStepper(
            value = HyroxFormat.kg(current), unit = if (station == Station.FARMERS_CARRY) "kg each" else "kg",
            onMinus = { set(current - 2.5) }, onPlus = { set(current + 2.5) },
        )
        Text(
            if (edited) "Edited by you (the standard is ${HyroxFormat.kg(base)} kg). The ladder uses this."
            else "This is the standard for your division. Change it if your gym's kit differs.",
            style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
        )
        if (edited) SecondaryButton("Reset to the standard", { onChange(null) }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun HistoryCard(station: Station, entries: List<com.calum.traininglog.core.StationEntry>, records: List<SessionRecord>, today: LocalDate) {
    SectionCard(title = "History") {
        if (entries.isEmpty()) {
            Text("Your first log will appear here", style = MaterialTheme.typography.bodyLarge, color = AppColors.textMuted)
            return@SectionCard
        }
        val weeks = remember(records, station, today) { ProgressStats.stationBest(records, station, today, 12) }
        val chart = remember(weeks) { HyroxFormat.chart(station, weeks) }
        if (chart != null && chart.values.size >= 2) {
            Text(
                if (chart.byLoad) "Heaviest load per week" else "Best time per week",
                style = MaterialTheme.typography.labelMedium, color = AppColors.textMuted,
            )
            LineChart(
                series = listOf(Series(if (chart.byLoad) "Load" else "Time", chart.values, AppColors.primary)),
                xLabels = chart.weekIndexes.map { weeks[it].weekStart.format(dayFmt) },
                height = 180.dp,
                showLegend = false,
                yFormat = if (chart.byLoad) ({ "${it.roundToInt()}" }) else ({ HyroxFormat.time(it.roundToInt()) }),
                endFormat = if (chart.byLoad) ({ "${HyroxFormat.kg(it.toDouble())} kg" }) else ({ HyroxFormat.time(it.roundToInt()) }),
            )
        }
        Text("Recent logs", style = MaterialTheme.typography.titleSmall, color = AppColors.textPrimary)
        entries.take(5).forEach { e ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(e.date.format(dayFmt), Modifier.width(56.dp), style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted)
                Text(HyroxFormat.logLine(station, e.log), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = AppColors.textPrimary)
            }
        }
    }
}

@Preview(widthDp = 411, heightDp = 2600)
@Composable
private fun StationPreview() {
    TrainingTheme {
        HyroxStationContent(Station.SLED_PUSH, emptyList(), UserSettings(), LocalDate.now(), {}, {}, {}, {})
    }
}
