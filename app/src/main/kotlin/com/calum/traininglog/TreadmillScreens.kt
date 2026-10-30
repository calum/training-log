package com.calum.traininglog

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.calum.traininglog.core.Effort
import com.calum.traininglog.core.Segment
import com.calum.traininglog.core.Timeline
import com.calum.traininglog.core.TreadmillPlan
import com.calum.traininglog.core.TreadmillStyle
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.AppShapes
import com.calum.traininglog.ui.AppType
import com.calum.traininglog.ui.BigActionButton
import com.calum.traininglog.ui.ConfettiBurst
import com.calum.traininglog.ui.Pill
import com.calum.traininglog.ui.SecondaryButton
import com.calum.traininglog.ui.SectionCard
import com.calum.traininglog.ui.Spacing
import com.calum.traininglog.ui.TrainingTheme
import com.calum.traininglog.ui.TreadmillReadout

// ---------------- Treadmill ----------------

private fun mmss(sec: Int) = "${sec / 60}:${"%02d".format(sec % 60)}"

@Composable
fun TreadmillSetupScreen(vm: AppViewModel) {
    val plan by vm.pendingTreadmill.collectAsState()
    val p = plan ?: run { vm.home(); return }
    TreadmillSetupContent(
        p, onStart = { vm.startTreadmill() }, onOtherStyle = { vm.otherStyle() }, onBack = { vm.go(Screen.Start) },
    )
}

@Composable
private fun TreadmillSetupContent(p: TreadmillPlan, onStart: () -> Unit, onOtherStyle: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(AppColors.background).verticalScroll(rememberScrollState())
            .statusBarsPadding().padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(p.style.label, style = MaterialTheme.typography.headlineLarge, color = AppColors.textPrimary)
            Text("${p.totalSec / 60} minutes", style = MaterialTheme.typography.titleMedium, color = AppColors.textMuted)
        }
        SectionCard(title = "Your session") {
            PlanProfile(p.segments, Modifier.fillMaxWidth().height(150.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                LegendDot(AppColors.primary, "Speed")
                LegendDot(AppColors.info, "Incline")
            }
            p.segments.forEach { s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(mmss(s.seconds), style = AppType.numberS, color = AppColors.textMuted, modifier = Modifier.padding(end = Spacing.md))
                    Text(s.label, style = MaterialTheme.typography.titleSmall, color = AppColors.textPrimary, modifier = Modifier.weight(1f))
                    Text("${s.speedKmh}", style = AppType.numberS, color = AppColors.primary)
                    Text(" km/h  ", style = MaterialTheme.typography.labelMedium, color = AppColors.textMuted)
                    Text("${s.inclinePct.toInt()}%", style = AppType.numberS, color = AppColors.info)
                }
            }
        }
        Text(
            "Speed and incline are changed by hand. You get a beep about 15 seconds before each change, and again when it's time.",
            style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
        )
        BigActionButton("Start", onStart, Modifier.heightIn(min = 96.dp))
        SecondaryButton("Different style", onOtherStyle, Modifier.fillMaxWidth())
        SecondaryButton("Back", onBack, Modifier.fillMaxWidth(), accent = AppColors.textMuted)
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Box(Modifier.size(12.dp).clip(AppShapes.chip).background(color))
        Text(text, style = MaterialTheme.typography.labelMedium, color = AppColors.textMuted)
    }
}

/**
 * Mini profile chart: incline as soft blue bars, speed as a mint step line, both over time.
 * [progressSec] (optional) draws a playhead.
 */
@Composable
private fun PlanProfile(segments: List<Segment>, modifier: Modifier = Modifier, progressSec: Int? = null) {
    val mint = AppColors.primary
    val blue = AppColors.info
    val grid = AppColors.outline.copy(alpha = 0.5f)
    val head = AppColors.energy
    Canvas(modifier) {
        val total = segments.sumOf { it.seconds }.coerceAtLeast(1)
        val maxSpeed = (segments.maxOfOrNull { it.speedKmh } ?: 6.0).toFloat() * 1.15f
        val maxInc = (segments.maxOfOrNull { it.inclinePct } ?: 10.0).toFloat().coerceAtLeast(4f) * 1.15f
        val h = size.height
        val w = size.width
        for (i in 1..3) drawLine(grid, Offset(0f, h * i / 4f), Offset(w, h * i / 4f), 1.dp.toPx())
        var x = 0f
        val path = Path()
        segments.forEachIndexed { i, s ->
            val sw = w * s.seconds / total
            val ih = h * (s.inclinePct.toFloat() / maxInc)
            drawRect(blue.copy(alpha = 0.28f), Offset(x + 1f, h - ih), Size((sw - 2f).coerceAtLeast(1f), ih))
            val y = h - h * (s.speedKmh.toFloat() / maxSpeed)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            path.lineTo(x + sw, y)
            x += sw
        }
        drawPath(path, mint, style = Stroke(width = 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        if (progressSec != null) {
            val px = w * progressSec.coerceIn(0, total) / total
            drawLine(head, Offset(px, 0f), Offset(px, h), 3.dp.toPx())
        }
    }
}

@Composable
fun PlayerContent(vm: AppViewModel, compact: Boolean) {
    val ui by TreadmillEngine.ui.collectAsState()
    if (ui.state == null) {
        if (!compact) vm.home()
        return
    }
    if (!compact) KeepScreenOn()
    TreadmillPlayerBody(
        ui, compact,
        onTogglePause = { vm.togglePause() },
        onAdjust = { vm.adjustSegment(it) },
        onEnd = { vm.endTreadmill() },
    )
}

@Composable
private fun TreadmillPlayerBody(
    ui: TreadmillEngine.Ui,
    compact: Boolean,
    onTogglePause: () -> Unit,
    onAdjust: (Int) -> Unit,
    onEnd: () -> Unit,
) {
    val s = ui.state ?: return
    val left = mmss(s.secondsLeft)
    val progress = if (ui.totalSec == 0) 0f else (s.elapsedSec / ui.totalSec.toFloat()).coerceIn(0f, 1f)
    val warn = !ui.finished && !ui.paused && s.next != null && s.secondsLeft <= 15

    if (ui.flashing) {
        // Full-screen flash: high-contrast mint with the current setting in giant dark type.
        Column(
            Modifier.fillMaxSize().background(AppColors.primary).padding(if (compact) 6.dp else Spacing.screen),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            val dark = Color(0xFF05100B)
            if (compact) {
                Text("${s.current.speedKmh} km/h", color = dark, style = AppType.numberL)
                Text("${s.current.inclinePct.toInt()}% incline", color = dark, style = AppType.numberM)
            } else {
                Text("CHANGE", color = dark, style = AppType.eyebrow)
                Text("${s.current.speedKmh}", color = dark, style = AppType.numberHero)
                Text("km/h", color = dark, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(Spacing.md))
                Text("${s.current.inclinePct.toInt()}%", color = dark, style = AppType.numberXL)
                Text("incline", color = dark, style = MaterialTheme.typography.titleLarge)
                s.next?.let {
                    Spacer(Modifier.height(Spacing.lg))
                    Text("Next: ${it.speedKmh} km/h · ${it.inclinePct.toInt()}%", color = dark, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        return
    }

    if (compact) {
        Column(
            Modifier.fillMaxSize().background(AppColors.background).padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${s.current.speedKmh}", style = AppType.numberXL.copy(brush = AppColors.gradientPrimary), maxLines = 1)
                Text("km/h", style = MaterialTheme.typography.labelLarge, color = AppColors.textMuted, modifier = Modifier.padding(bottom = 8.dp))
                Text("${s.current.inclinePct.toInt()}%", style = AppType.numberL, color = AppColors.info, maxLines = 1)
            }
            Text(
                if (ui.finished) "Done" else if (ui.paused) "Paused" else left,
                style = AppType.numberM, color = if (warn) AppColors.energy else AppColors.textPrimary,
            )
            s.next?.let {
                Text("next ${it.speedKmh} / ${it.inclinePct.toInt()}%", style = MaterialTheme.typography.labelMedium, color = AppColors.textMuted, maxLines = 1)
            }
            Spacer(Modifier.height(4.dp))
            ProgressBar(progress, Modifier.fillMaxWidth().height(6.dp))
        }
        return
    }

    Column(
        Modifier.fillMaxSize().background(AppColors.background).verticalScroll(rememberScrollState())
            .statusBarsPadding().padding(Spacing.screen),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        TreadmillReadout(
            speed = s.current.speedKmh.toFloat(), incline = s.current.inclinePct.toFloat(),
            elapsed = mmss(s.elapsedSec), phaseLabel = s.current.label,
        )
        // Time left in this segment
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (ui.finished) "SESSION COMPLETE" else if (ui.paused) "PAUSED" else "LEFT IN THIS PART",
                style = AppType.eyebrow, color = if (ui.paused) AppColors.energy else AppColors.textMuted,
            )
            if (!ui.finished) Text(left, style = AppType.numberXL, color = if (warn) AppColors.energy else AppColors.textPrimary)
        }
        // Overall progress
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            ProgressBar(progress, Modifier.fillMaxWidth().height(20.dp))
            Text(
                "${s.elapsedSec / 60} of ${ui.totalSec / 60} min", style = MaterialTheme.typography.labelLarge,
                color = AppColors.textMuted, modifier = Modifier.align(Alignment.End),
            )
        }
        s.next?.let { n -> NextChangeCard(n, s.secondsLeft, warn) }
        Spacer(Modifier.height(Spacing.sm))
        if (!ui.finished) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton("−30s", { onAdjust(-30) }, Modifier.weight(1f).heightIn(min = 72.dp))
                SecondaryButton("+30s", { onAdjust(30) }, Modifier.weight(1f).heightIn(min = 72.dp))
            }
            BigActionButton(if (ui.paused) "Resume" else "Pause", onTogglePause)
            SecondaryButton("End session", onEnd, Modifier.fillMaxWidth(), accent = AppColors.textMuted)
        } else {
            BigActionButton("Finish", onEnd)
        }
    }
}

@Composable
private fun ProgressBar(progress: Float, modifier: Modifier) {
    Box(modifier.clip(AppShapes.chip).background(AppColors.track)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxSize().clip(AppShapes.chip).background(AppColors.gradientPrimary))
    }
}

@Composable
private fun NextChangeCard(n: Segment, secondsLeft: Int, warn: Boolean) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(450, easing = LinearEasing), RepeatMode.Reverse), label = "p",
    )
    val glow = if (warn) pulse else 0f
    val accent = if (warn) AppColors.energy else AppColors.info
    Row(
        Modifier.fillMaxWidth()
            .clip(AppShapes.medium)
            .background(accent.copy(alpha = 0.10f + 0.22f * glow))
            .border(if (warn) 3.dp else 1.dp, accent.copy(alpha = if (warn) 0.5f + 0.5f * glow else 0.4f), AppShapes.medium)
            .padding(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(if (warn) "CHANGE IN ${secondsLeft}s" else "NEXT CHANGE", style = AppType.eyebrow, color = accent)
            Text(n.label, style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary)
        }
        Text("${n.speedKmh}", style = AppType.numberL, color = AppColors.textPrimary)
        Text(" km/h  ", style = MaterialTheme.typography.labelMedium, color = AppColors.textMuted)
        Text("${n.inclinePct.toInt()}%", style = AppType.numberL, color = AppColors.textPrimary)
    }
}

@Composable
fun TreadmillDoneScreen(vm: AppViewModel) {
    var burst by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { burst = 1 }
    Box(Modifier.fillMaxSize().background(AppColors.background)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Text("Nice work", style = MaterialTheme.typography.displaySmall, color = AppColors.textPrimary)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text("How did that feel?", style = MaterialTheme.typography.headlineSmall, color = AppColors.textPrimary)
                Text("This tunes next time's speed and incline.", style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted)
            }
            EffortTile("Too easy", "Turn it up next time", AppColors.energy) { vm.saveTreadmill(Effort.TOO_EASY) }
            EffortTile("Just right", "Keep it exactly like this", AppColors.primary) { vm.saveTreadmill(Effort.JUST_RIGHT) }
            EffortTile("Too hard", "Ease it back a little", AppColors.info) { vm.saveTreadmill(Effort.TOO_HARD) }
            SecondaryButton("Skip", { vm.saveTreadmill(null) }, Modifier.fillMaxWidth(), accent = AppColors.textMuted)
        }
        ConfettiBurst(burst, Modifier.fillMaxSize(), particleCount = 120)
    }
}

@Composable
private fun EffortTile(title: String, subtitle: String, accent: Color, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Column(
        Modifier.fillMaxWidth().heightIn(min = 96.dp)
            .clip(AppShapes.large)
            .background(accent.copy(alpha = 0.16f))
            .border(2.dp, accent.copy(alpha = 0.6f), AppShapes.large)
            .clickable { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() }
            .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = accent)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted)
    }
}

// ---------------- Previews ----------------

private val previewPlan = TreadmillPlan(
    TreadmillStyle.PYRAMID,
    listOf(
        Segment(300, 5.0, 6.0, "Warm-up"), Segment(240, 5.5, 8.0, "Build"), Segment(240, 6.0, 10.0, "Peak"),
        Segment(240, 5.5, 8.0, "Ease"), Segment(180, 4.0, 2.0, "Cool-down"),
    ),
)

private fun previewUi(at: Int, flashing: Boolean = false): TreadmillEngine.Ui {
    val tl = Timeline(previewPlan.segments)
    return TreadmillEngine.Ui(active = true, flashing = flashing, plan = previewPlan, state = tl.stateAt(at), totalSec = tl.totalSec)
}

@Preview(widthDp = 380, heightDp = 900)
@Composable
private fun SetupPreview() = TrainingTheme { TreadmillSetupContent(previewPlan, {}, {}, {}) }

@Preview(widthDp = 380, heightDp = 900)
@Composable
private fun PlayerPreview() = TrainingTheme { TreadmillPlayerBody(previewUi(515), false, {}, {}, {}) }

@Preview(widthDp = 320, heightDp = 180)
@Composable
private fun PlayerPipPreview() = TrainingTheme { TreadmillPlayerBody(previewUi(515), true, {}, {}, {}) }

@Preview(widthDp = 320, heightDp = 180)
@Composable
private fun PlayerPipFlashPreview() = TrainingTheme { TreadmillPlayerBody(previewUi(515, true), true, {}, {}, {}) }
