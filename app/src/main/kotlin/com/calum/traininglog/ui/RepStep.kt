package com.calum.traininglog.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/*
 * Rep-based steps ("do 8 reps, then press Next"), built to be reused by any screen with sets: the knee care calf
 * sets use them now, the guided gym screen uses them too. Three pieces, used in this order for each set:
 *
 *   [RepStepCard]  the set itself: name, "Set 2 of 4", the target reps as huge numerals, the tempo, the load, and ONE
 *                  very large "Done, next" button at the bottom. Small -/+ steppers correct the load and the reps done.
 *   [RirRow]       an optional one-tap effort row (reps left in the tank). It never blocks: ignore it and carry on.
 *   [RestStepCard] the rest between sets: a countdown ring, "Skip rest", and a "Next up" preview.
 */

private fun fmtKg(x: Double) = if (x % 1.0 == 0.0) x.toInt().toString() else "%.1f".format(x)

/**
 * One rep-based set.
 *
 * @param name exercise name; [setNumber] of [setCount] is shown as "Set 2 of 4" (pass [setCount] = 0 to hide it).
 * @param targetReps the reps to aim for, shown huge ("8 reps"); [tempo] such as "3 s up, 3 s down".
 * @param reps reps actually done (starts at [targetReps]); [onRepsChange] gets the corrected number.
 * @param weightKg the load; null shows a blank for the user to fill in (a first-ever set). [onWeightChange] gets the new
 * load, stepping by [weightStepKg] (never below 0). Pass [weightKg] = null and [onWeightChange] = null to hide the load.
 * @param onDone the big "Done, next" button ([doneLabel]). With [pinDone] (the card must then have a bounded height, e.g.
 * `Modifier.weight(1f)` in a non-scrolling column) the button sits at the very bottom of the screen, within thumb reach.
 * @param cue optional one or two lines of form advice, [hint] a small line such as "Last time: 25 kg x 8, 8, 7",
 * [nextUp] an optional preview of what follows.
 */
@Composable
fun RepStepCard(
    name: String,
    setNumber: Int,
    setCount: Int,
    targetReps: Int,
    tempo: String,
    reps: Int,
    onRepsChange: (Int) -> Unit,
    weightKg: Double?,
    onWeightChange: ((Double) -> Unit)?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    weightStepKg: Double = 1.0,
    cue: String? = null,
    hint: String? = null,
    nextUp: String? = null,
    doneLabel: String = "Done, next",
    pinDone: Boolean = false,
    /** Replaces the "SET 2 OF 4" pill (for example "WARM-UP 2 OF 5"). */
    badge: String? = null,
    /** False hides the "REPS DONE" stepper (a warm-up set has nothing to correct). */
    repsStepper: Boolean = true,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md), horizontalAlignment = Alignment.CenterHorizontally) {
        if (badge != null) Pill(badge, color = AppColors.primary, filled = true)
        else if (setCount > 0) Pill("SET $setNumber OF $setCount", color = AppColors.primary, filled = true)
        Text(name, style = MaterialTheme.typography.headlineMedium, color = AppColors.textPrimary, textAlign = TextAlign.Center)

        Column(
            Modifier.fillMaxWidth().clip(AppShapes.large).background(AppColors.surfaceRaised).padding(vertical = Spacing.lg, horizontal = Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("DO", style = AppType.eyebrow, color = AppColors.textMuted)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("$targetReps", style = AppType.numberHero, color = AppColors.primary)
                Text(if (targetReps == 1) "rep" else "reps", style = MaterialTheme.typography.headlineMedium, color = AppColors.textPrimary, modifier = Modifier.padding(bottom = 18.dp))
            }
            if (tempo.isNotBlank()) Text(tempo, style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary, textAlign = TextAlign.Center)
            if (weightKg != null || onWeightChange != null) {
                Spacer(Modifier.size(Spacing.xs))
                Text(
                    if (weightKg == null) "Enter your load below" else if (weightKg <= 0.0) "Bodyweight" else "${fmtKg(weightKg)} kg",
                    style = MaterialTheme.typography.headlineSmall, color = AppColors.energy, textAlign = TextAlign.Center,
                )
            }
        }
        cue?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = AppColors.textPrimary, textAlign = TextAlign.Center) }
        hint?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = AppColors.info, textAlign = TextAlign.Center) }

        // Small correctors: out of the way of the one big button.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            if (onWeightChange != null) {
                MiniStepper(
                    "LOAD", weightKg?.let { if (it <= 0.0) "0" else fmtKg(it) } ?: "--", "kg",
                    onMinus = { onWeightChange(((weightKg ?: 0.0) - weightStepKg).coerceAtLeast(0.0)) },
                    onPlus = { onWeightChange((weightKg ?: 0.0) + weightStepKg) },
                )
            }
            if (repsStepper) {
                MiniStepper(
                    "REPS DONE", "$reps", "reps",
                    onMinus = { onRepsChange((reps - 1).coerceAtLeast(0)) },
                    onPlus = { onRepsChange(reps + 1) },
                )
            }
        }
        nextUp?.let { NextUpLine(it) }
        if (pinDone) Spacer(Modifier.weight(1f))
        BigActionButton(doneLabel, onDone, Modifier.fillMaxWidth().heightIn(min = 96.dp))
    }
}

@Composable
private fun MiniStepper(label: String, value: String, unit: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = AppType.eyebrow, color = AppColors.textMuted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            RoundStep("-", "Less $label", onMinus)
            Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold), color = AppColors.textPrimary, maxLines = 1)
                Text(unit, style = MaterialTheme.typography.labelSmall, color = AppColors.textMuted)
            }
            RoundStep("+", "More $label", onPlus)
        }
    }
}

@Composable
private fun RoundStep(symbol: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(AppColors.surface).border(1.5.dp, AppColors.outline, CircleShape)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(symbol, style = MaterialTheme.typography.titleLarge, color = AppColors.textPrimary) }
}

@Composable
private fun NextUpLine(next: String) {
    Column(
        Modifier.fillMaxWidth().clip(AppShapes.medium).background(AppColors.info.copy(alpha = 0.10f))
            .border(1.dp, AppColors.info.copy(alpha = 0.4f), AppShapes.medium).padding(Spacing.md),
    ) {
        Text("NEXT UP", style = AppType.eyebrow, color = AppColors.info)
        Text(next, style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary)
    }
}

/**
 * Optional one-tap effort row: how many reps were left in the tank (0, 1, 2, 3+). Tap the chosen one again to clear it.
 * It never blocks anything: [rir] may stay null and the session carries on.
 */
@Composable
fun RirRow(rir: Int?, onRir: (Int?) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text("How many reps were left in the tank? (optional)", style = MaterialTheme.typography.labelLarge, color = AppColors.textMuted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            listOf(0, 1, 2, 3).forEach { v ->
                SelectChip(if (v == 3) "3+" else "$v", rir == v, { onRir(if (rir == v) null else v) }, Modifier.weight(1f))
            }
        }
    }
}

private fun mmss(sec: Int) = "${sec / 60}:${"%02d".format(sec % 60)}"

/**
 * The rest between sets: a countdown ring ([secondsLeft] of [totalSec]), "Skip rest", and a "Next up" preview. The caller
 * plays the beep when the rest ends. [content] (for example a [RirRow]) sits above the ring.
 */
@Composable
fun RestStepCard(
    secondsLeft: Int,
    totalSec: Int,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Rest",
    nextUp: String? = null,
    paused: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val track = AppColors.track
    val color = AppColors.info
    val fraction = if (totalSec <= 0) 0f else (secondsLeft.toFloat() / totalSec).coerceIn(0f, 1f)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md), horizontalAlignment = Alignment.CenterHorizontally) {
        Pill(if (paused) "PAUSED" else title.uppercase(), color = if (paused) AppColors.energy else color, filled = paused)
        content()
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(220.dp)) {
                val sw = 16.dp.toPx()
                val arc = Size(size.width - sw, size.height - sw)
                drawArc(track, 0f, 360f, false, Offset(sw / 2, sw / 2), arc, style = Stroke(sw))
                drawArc(color, -90f, 360f * fraction, false, Offset(sw / 2, sw / 2), arc, style = Stroke(sw, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(mmss(secondsLeft), style = AppType.numberXL, color = AppColors.textPrimary)
                Text("of ${mmss(totalSec)}", style = MaterialTheme.typography.labelLarge, color = AppColors.textMuted)
            }
        }
        nextUp?.let { NextUpLine(it) }
        SecondaryButton("Skip rest", onSkip, Modifier.fillMaxWidth().heightIn(min = 64.dp))
    }
}

@Preview(name = "Rep step", widthDp = 380, heightDp = 860)
@Composable
private fun RepStepPreview() = TrainingTheme {
    Column(Modifier.background(AppColors.background).padding(Spacing.screen)) {
        RepStepCard(
            "Calf raise (straight knee)", 2, 4, 8, "3 s up, brief pause, 3 s down", 8, {}, 25.0, {}, {},
            cue = "Weight in one hand, other hand on a wall. Knees straight.", hint = "Last time: 25 kg x 8, 8, 7", nextUp = "Rest, then set 3",
        )
    }
}

@Preview(name = "Rest step", widthDp = 380, heightDp = 760)
@Composable
private fun RestStepPreview() = TrainingTheme {
    Column(Modifier.background(AppColors.background).padding(Spacing.screen)) {
        RestStepCard(48, 75, {}, nextUp = "Calf raise (straight knee), set 3") { RirRow(1, {}) }
    }
}
