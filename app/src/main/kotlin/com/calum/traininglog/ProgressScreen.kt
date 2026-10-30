package com.calum.traininglog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calum.traininglog.core.LiftProgress
import com.calum.traininglog.core.ProgressData
import com.calum.traininglog.core.ProgressStats
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.AppType
import com.calum.traininglog.ui.HeroCard
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
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

private val monthFmt = DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH)

@Composable
fun ProgressScreen(vm: AppViewModel) {
    val data by vm.data.collectAsState()
    ProgressContent(data.records, LocalDate.now(), data.profile.settings, onBack = { vm.home() })
}

@Composable
internal fun ProgressContent(
    records: List<SessionRecord>,
    today: LocalDate,
    settings: com.calum.traininglog.core.UserSettings = com.calum.traininglog.core.UserSettings(),
    onBack: () -> Unit,
    initialWeeks: Int = 12,
    initialLift: String? = null,
    initialEstimate: Boolean = false,
) {
    var weeks by remember { mutableIntStateOf(initialWeeks) }
    var liftId by remember { mutableStateOf(initialLift) }
    var estimate by remember { mutableStateOf(initialEstimate) }
    val data = remember(records, today, weeks, settings) { ProgressStats.build(records, today, weeks, settings) }

    Column(
        Modifier.fillMaxSize().background(AppColors.background).statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text("Your progress", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, color = AppColors.textPrimary)
            SecondaryButton("Back", onBack)
        }
        if (data.isEmpty) {
            SectionCard {
                Text("Your first sessions will appear here", style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary)
                Text(
                    "Once you have logged a session, this page fills in with your lifts, your weeks and your treadmill time. " +
                        "There is no rush.",
                    style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
                )
            }
            Spacer(Modifier.navigationBarsPadding().height(Spacing.xl))
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            listOf(12 to "3 months", 26 to "6 months", 52 to "1 year").forEach { (w, label) ->
                SelectChip(label, weeks == w, { weeks = w })
            }
        }

        val selected = data.lifts.firstOrNull { it.exerciseId == liftId } ?: data.lifts.firstOrNull()
        Headline(data, selected)
        TotalsRow(data)
        LiftsCard(data, selected, estimate, { liftId = it }, { estimate = it })
        WeeklyCard(data)
        TreadmillCard(data)
        PrCard(data)
        Text(
            "Only what you did is counted here. Quiet weeks are just quiet.",
            style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
        )
        Spacer(Modifier.navigationBarsPadding().height(Spacing.xl))
    }
}

private fun kg(x: Double): String = if (x % 1.0 == 0.0) x.toInt().toString() else "%.1f".format(Locale.ENGLISH, x)

private fun sinceText(d: LocalDate, today: LocalDate): String =
    if (java.time.temporal.ChronoUnit.DAYS.between(d, today) < 45) "you started" else d.format(monthFmt)

@Composable
private fun Headline(data: ProgressData, lift: LiftProgress?) {
    val today = data.weekStarts.last().plusDays(6)
    HeroCard(brush = AppColors.gradientPrimary) {
        Text("SINCE YOU STARTED", style = AppType.eyebrow, color = AppColors.onHero.copy(alpha = 0.8f))
        val first = data.firstSessionDate
        if (lift != null && lift.changeKg > 0.0) {
            Text(
                "${lift.name} is up ${kg(lift.changeKg)} kg",
                style = MaterialTheme.typography.headlineMedium, color = AppColors.onHero,
            )
            Text(
                "Since ${sinceText(lift.firstDate, today)}: ${kg(lift.firstKg)} kg, now ${kg(lift.lastKg)} kg.",
                style = MaterialTheme.typography.bodyLarge, color = AppColors.onHero.copy(alpha = 0.9f),
            )
        } else if (lift != null) {
            Text(
                "${lift.name}: ${kg(lift.lastKg)} kg",
                style = MaterialTheme.typography.headlineMedium, color = AppColors.onHero,
            )
            Text(
                "Your best so far is ${kg(lift.bestEverKg)} kg. Steady is good.",
                style = MaterialTheme.typography.bodyLarge, color = AppColors.onHero.copy(alpha = 0.9f),
            )
        } else {
            Text(
                "${data.totals.sessions} sessions done",
                style = MaterialTheme.typography.headlineMedium, color = AppColors.onHero,
            )
        }
        if (first != null) {
            Text(
                "${data.totals.sessions} sessions done since ${first.format(monthFmt)}. Well done.",
                style = MaterialTheme.typography.bodyMedium, color = AppColors.onHero.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun TotalsRow(data: ProgressData) {
    val t = data.totals
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        StatTile("Sessions", "${t.sessions}", Modifier.weight(1f), accent = AppColors.primary, compact = true)
        StatTile("Time moving", "%.1f".format(Locale.ENGLISH, t.hours), Modifier.weight(1f), unit = "hrs", accent = AppColors.energy, compact = true)
        StatTile("Lifted (est.)", "%.1f".format(Locale.ENGLISH, t.tonnes), Modifier.weight(1f), unit = "t", accent = AppColors.reward, compact = true)
    }
}

@Composable
private fun LiftsCard(data: ProgressData, selected: LiftProgress?, estimate: Boolean, onPick: (String) -> Unit, onEstimate: (Boolean) -> Unit) {
    SectionCard(title = "Your lifts") {
        if (selected == null) {
            Text("Log a gym session and your lifts will be charted here.", style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted)
            return@SectionCard
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            data.lifts.forEach { l -> SelectChip(l.name, selected.exerciseId == l.exerciseId, { onPick(l.exerciseId) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SelectChip("Top weight", !estimate, { onEstimate(false) }, accent = AppColors.info)
            SelectChip("Estimated 1RM", estimate, { onEstimate(true) }, accent = AppColors.info)
        }
        val raw = if (estimate) selected.e1rmKg else selected.topKg
        val first = raw.indexOfFirst { it != null }
        val values = if (first < 0) emptyList() else raw.drop(first).map { (it ?: 0.0).toFloat() }
        val labels = if (first < 0) emptyList() else data.weekStarts.drop(first).map { it.format(shortFmt) }
        if (values.size < 2) {
            Text(
                if (first < 0) "Nothing logged for ${selected.name} in this period."
                else "${selected.name} first appears this week at ${kg(raw.last() ?: 0.0)} kg. The line grows as you add sessions.",
                style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
            )
        } else {
            LineChart(
                series = listOf(Series(if (estimate) "Estimated 1RM" else "Top weight", values, if (estimate) AppColors.reward else AppColors.primary, dashed = estimate)),
                xLabels = labels,
                height = 200.dp,
                showLegend = false,
                yFormat = { it.roundToInt().toString() },
                endFormat = { "${kg(Math.round(it * 2f) / 2.0)} kg" },
            )
        }
        Text(
            if (estimate) "Estimate only: the Epley formula guesses a one-rep max from your heaviest set (weight x (1 + reps / 30)). You have not necessarily lifted it."
            else "Heaviest working weight each week, in kg. Quiet weeks carry your last weight forward.",
            style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            StatTile("Best ever", kg(selected.bestEverKg), Modifier.weight(1f), unit = "kg", accent = AppColors.energy, compact = true)
            StatTile("First", kg(selected.firstKg), Modifier.weight(1f), unit = "kg", accent = AppColors.info, compact = true)
            StatTile("Sessions", "${selected.sessions}", Modifier.weight(1f), accent = AppColors.primary, compact = true)
        }
        if (data.customExercises.isNotEmpty()) {
            Text(
                "Also logged: ${data.customExercises.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
            )
        }
    }
}

private val shortFmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

@Composable
private fun WeeklyCard(data: ProgressData) {
    val w = data.weekly
    val total = w.sumOf { it.sessions }
    val best = w.maxOf { it.sessions }
    SectionCard(title = "Sessions each week") {
        Text(
            if (total == 0) "A quiet stretch. Whenever you are ready, your next session will show up here."
            else "$total sessions in ${w.size} weeks" + if (best > 0) ", up to $best in a single week." else ".",
            style = MaterialTheme.typography.bodyMedium, color = AppColors.textPrimary,
        )
        BarChart(
            bars = w.map { listOf(it.gym.toFloat(), it.treadmill.toFloat(), it.other.toFloat()) },
            colors = listOf(AppColors.primary, AppColors.energy, AppColors.reward),
            xLabels = data.weekStarts.map { it.format(shortFmt) },
            height = 170.dp,
        )
        Legend(listOf("Gym" to AppColors.primary, "Treadmill" to AppColors.energy, "Core / hike" to AppColors.reward))
    }
}

@Composable
private fun TreadmillCard(data: ProgressData) {
    val mins = data.weekly.map { it.treadmillMinutes }
    val total = mins.sum()
    SectionCard(title = "Treadmill minutes") {
        if (total == 0) {
            Text("No treadmill time in this period yet.", style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted)
            return@SectionCard
        }
        Text(
            "$total minutes over ${mins.count { it > 0 }} weeks, ${mins.max()} in your longest week.",
            style = MaterialTheme.typography.bodyMedium, color = AppColors.textPrimary,
        )
        BarChart(
            bars = mins.map { listOf(it.toFloat()) },
            colors = listOf(AppColors.energy),
            xLabels = data.weekStarts.map { it.format(shortFmt) },
            height = 150.dp,
            unit = "min",
        )
        if (data.stylesTried.isNotEmpty()) {
            Text("STYLES TRIED", style = AppType.eyebrow, color = AppColors.textMuted)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                data.stylesTried.entries.sortedByDescending { it.value }.forEach { (s, n) ->
                    Pill("${s.label} x$n", color = AppColors.energy)
                }
            }
        }
    }
}

@Composable
private fun PrCard(data: ProgressData) {
    SectionCard(title = "Personal records") {
        if (data.prs.isEmpty()) {
            Text("Your first personal record is waiting for you.", style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted)
            return@SectionCard
        }
        data.prs.take(8).forEach { p ->
            Row(
                Modifier.fillMaxWidth().background(AppColors.surfaceRaised, com.calum.traininglog.ui.AppShapes.medium).padding(Spacing.md),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Pill(shortFmt.format(p.date), Modifier.width(72.dp), color = AppColors.energy)
                Text(p.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = AppColors.textPrimary)
            }
        }
        if (data.prs.size > 8) {
            Text("and ${data.prs.size - 8} more on the Trophies screen.", style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
        }
    }
}

@Composable
private fun Legend(items: List<Pair<String, Color>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (name, c) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(c))
                Text(name, style = MaterialTheme.typography.labelMedium, color = AppColors.textMuted)
            }
        }
    }
}

private fun niceCountStep(max: Float): Float {
    val raw = max / 3f
    return listOf(1f, 2f, 5f, 10f, 15f, 20f, 30f, 50f, 100f, 200f).firstOrNull { it >= raw } ?: ceil(raw)
}

/**
 * Stacked bar chart on a Canvas. [bars] holds one list of segment values per bar (bottom segment first, coloured
 * by [colors]). Empty bars draw nothing but a faint baseline tick, so quiet weeks are calm, not alarming.
 */
@Composable
internal fun BarChart(
    bars: List<List<Float>>,
    colors: List<Color>,
    xLabels: List<String>,
    modifier: Modifier = Modifier,
    height: Dp = 170.dp,
    unit: String = "",
) {
    val measurer = rememberTextMeasurer()
    val muted = AppColors.textMuted
    val grid = AppColors.outline.copy(alpha = 0.45f)
    val track = AppColors.track
    val labelStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = muted)
    Canvas(modifier.fillMaxWidth().height(height)) {
        val maxTotal = bars.maxOfOrNull { it.sum() } ?: 0f
        val step = niceCountStep(maxTotal.coerceAtLeast(1f))
        val top = ceil(maxTotal.coerceAtLeast(1f) / step) * step
        val ticks = generateSequence(0f) { it + step }.takeWhile { it <= top + 1e-4f }.toList()
        val tickText = ticks.map { measurer.measure(it.roundToInt().toString(), labelStyle) }
        val left = (tickText.maxOfOrNull { it.size.width } ?: 0) + 12.dp.toPx()
        val right = 4.dp.toPx()
        val bottom = 24.dp.toPx()
        val topPad = 8.dp.toPx()
        val plotW = size.width - left - right
        val plotH = size.height - topPad - bottom
        fun py(v: Float) = topPad + plotH * (1f - v / top)
        ticks.forEachIndexed { k, v ->
            val y = py(v)
            drawLine(grid, Offset(left, y), Offset(size.width - right, y), 1.dp.toPx())
            val t = tickText[k]
            drawText(t, topLeft = Offset(left - 8.dp.toPx() - t.size.width, y - t.size.height / 2f))
        }
        val n = bars.size.coerceAtLeast(1)
        val slot = plotW / n
        val barW = (slot * 0.68f).coerceAtMost(28.dp.toPx())
        val r = (barW / 4f).coerceAtMost(4.dp.toPx())
        bars.forEachIndexed { i, segs ->
            val cx = left + slot * (i + 0.5f)
            val x = cx - barW / 2f
            if (segs.sum() <= 0f) {
                drawRoundRect(track, Offset(x, py(0f) - 3.dp.toPx()), Size(barW, 3.dp.toPx()), CornerRadius(1.5.dp.toPx()))
                return@forEachIndexed
            }
            var acc = 0f
            val rects = segs.mapIndexedNotNull { k, v ->
                if (v <= 0f) null else {
                    val y1 = py(acc + v)
                    val y0 = py(acc)
                    acc += v
                    Triple(k, y1, y0)
                }
            }
            // Draw top-down: the top segment is rounded and extends under the one below it.
            rects.asReversed().forEachIndexed { j, (k, y1, y0) ->
                val c = colors[k % colors.size]
                if (j == 0) {
                    val ext = if (rects.size > 1) r * 2 else 0f
                    clipRect(left = 0f, top = 0f, right = size.width, bottom = py(0f)) {
                        drawRoundRect(c, Offset(x, y1), Size(barW, y0 - y1 + ext), CornerRadius(r))
                    }
                } else {
                    drawRect(c, Offset(x, y1), Size(barW, y0 - y1))
                }
            }
        }
        val stride = ceil(xLabels.size / 5f).toInt().coerceAtLeast(1)
        xLabels.forEachIndexed { i, s ->
            if ((xLabels.size - 1 - i) % stride == 0) {
                val t = measurer.measure(s, labelStyle)
                val x = (left + slot * (i + 0.5f) - t.size.width / 2f).coerceIn(0f, size.width - t.size.width)
                drawText(t, topLeft = Offset(x, size.height - bottom + 6.dp.toPx()))
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 400, heightDp = 1800)
@Composable
private fun ProgressPreview() {
    TrainingTheme { ProgressContent(emptyList(), LocalDate.of(2026, 10, 30), onBack = {}) }
}
