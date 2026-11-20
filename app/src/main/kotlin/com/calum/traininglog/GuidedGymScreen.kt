package com.calum.traininglog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.calum.traininglog.core.Exercise
import com.calum.traininglog.core.MissReason
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.AppShapes
import com.calum.traininglog.ui.AppType
import com.calum.traininglog.ui.BigActionButton
import com.calum.traininglog.ui.Pill
import com.calum.traininglog.ui.RepStepCard
import com.calum.traininglog.ui.RestStepCard
import com.calum.traininglog.ui.RirRow
import com.calum.traininglog.ui.SecondaryButton
import com.calum.traininglog.ui.SectionCard
import com.calum.traininglog.ui.SelectChip
import com.calum.traininglog.ui.Spacing
import com.calum.traininglog.ui.TrainingTheme

/*
 * Guided gym mode: ONE set at a time, built on the rep-step pattern (see ui/RepStep.kt).
 *
 *   Intro   "Next exercise": name, what to do, Start
 *   Warmup  "Warm-up 2 of 5": Done, next
 *   Work    "Set 2 of 3": huge target reps, the load, small steppers, ONE big "Done, next"
 *   Rest    optional effort row, countdown ring, Skip rest, "Next up" (the beep and flash live in GymScreen)
 *   Finish  every set done: Finish session
 *
 * There is no extra state: what to show is derived from [GymLive] by [GymGuide], and every tap calls the same
 * [AppViewModel] functions as the list screen, so a guided session saves exactly the record a list session would.
 */

/** The guided gym body: the day, the sets progress and the per-exercise menu on top, then the current step. */
@Composable
internal fun GuidedGymBody(vm: AppViewModel, live: GymLive, remaining: Int, onFinish: () -> Unit, onAdd: () -> Unit) {
    val step = GymGuide.step(live)
    val rest = live.rest
    val (done, total) = GymGuide.progress(live)
    val targetEi = when (step) {
        is GymStep.Intro -> step.ei
        is GymStep.Warmup -> step.ei
        is GymStep.Work -> step.ei
        GymStep.Finish -> null
    }
    Column(Modifier.fillMaxSize()) {
        if (live.startedAt > 0L) SessionClockBar(live.startedAt, live.windowMinutes, live.plan.estimatedMinutes)
        GuidedHeader(vm, live, targetEi, done, total, onAdd, onFinish)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                GymGuide.showOverview(live) -> GuidedOverview(vm, live)
                rest != null -> GuidedRest(vm, live, rest, remaining, step, onFinish)
                step is GymStep.Intro -> GuidedIntro(vm, live, step.ei)
                step is GymStep.Warmup -> GuidedWarmup(vm, live, step.ei, step.wi)
                step is GymStep.Work -> GuidedWork(vm, live, step.ei, step.si)
                else -> GuidedFinish(vm, live, onFinish, onAdd)
            }
        }
    }
}

// ---------------- Header and menu ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuidedHeader(vm: AppViewModel, live: GymLive, ei: Int?, done: Int, total: Int, onAdd: () -> Unit, onFinish: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var swapping by remember { mutableStateOf(false) }
    val e = ei?.let { live.exercises.getOrNull(it) }
    val label = live.plan.dayLabel.let { if (it.length <= 1) "Gym $it" else it }
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary, maxLines = 1)
                Text("$done of $total sets done", style = MaterialTheme.typography.labelLarge, color = AppColors.textMuted)
            }
            if (live.plan.deload) Pill("Deload week", color = AppColors.info)
            Box {
                SelectChip("More", selected = menu, onClick = { menu = true }, modifier = Modifier.heightIn(min = 48.dp))
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (e != null && ei != null) {
                        val name = e.planned.exercise.name
                        if (vm.swapOptions(e.planned.exercise).isNotEmpty()) {
                            DropdownMenuItem(text = { Text("Swap $name") }, onClick = { menu = false; swapping = true })
                        }
                        DropdownMenuItem(text = { Text("Skip $name today") }, onClick = { menu = false; vm.removeExercise(ei) })
                        if (e.sets.any { it.done }) {
                            DropdownMenuItem(text = { Text("Skip the rest of $name") }, onClick = { menu = false; vm.closeExercise(ei) })
                        }
                        if (e.warmups.any { !it.done } && e.sets.none { it.done }) {
                            DropdownMenuItem(text = { Text("Skip warm-ups") }, onClick = { menu = false; vm.finishWarmups(ei) })
                        }
                        if (ei > 0) DropdownMenuItem(text = { Text("Do $name earlier") }, onClick = { menu = false; vm.moveExercise(ei, -1) })
                        if (ei < live.exercises.size - 1) DropdownMenuItem(text = { Text("Do $name later") }, onClick = { menu = false; vm.moveExercise(ei, 1) })
                    }
                    lastDoneSet(live)?.let { (uei, usi) ->
                        DropdownMenuItem(text = { Text("Undo the last set") }, onClick = { menu = false; vm.doneSet(uei, usi); vm.clearRest() })
                    }
                    DropdownMenuItem(text = { Text("Add exercise") }, onClick = { menu = false; onAdd() })
                    DropdownMenuItem(text = { Text("Overview (all sets)") }, onClick = { menu = false; vm.setGymGuided(false) })
                    DropdownMenuItem(text = { Text("Finish session now") }, onClick = { menu = false; onFinish() })
                    DropdownMenuItem(text = { Text("Discard session") }, onClick = { menu = false; vm.discardGym() })
                }
            }
        }
        ProgressBar(if (total == 0) 0f else done / total.toFloat())
    }
    if (swapping && e != null && ei != null) {
        AlertDialog(
            onDismissRequest = { swapping = false },
            containerColor = AppColors.surface,
            title = { Text("Swap ${e.planned.exercise.name}") },
            text = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    vm.swapOptions(e.planned.exercise).forEach { alt ->
                        SelectChip(alt.name, selected = false, onClick = { vm.swap(ei, alt.id); swapping = false }, accent = AppColors.info)
                    }
                }
            },
            confirmButton = { TextButton({ swapping = false }) { Text("Close") } },
        )
    }
}

/** The most recently finished set, in session order: where "Undo the last set" points. */
internal fun lastDoneSet(live: GymLive): Pair<Int, Int>? {
    val rest = live.rest
    if (rest != null && rest.ei in live.exercises.indices && rest.si in live.exercises[rest.ei].sets.indices && live.exercises[rest.ei].sets[rest.si].done) return rest.ei to rest.si
    for (ei in live.exercises.indices.reversed()) {
        val si = live.exercises[ei].sets.indexOfLast { it.done }
        if (si >= 0) return ei to si
    }
    return null
}

@Composable
private fun ProgressBar(progress: Float) {
    Box(Modifier.fillMaxWidth().height(8.dp).clip(AppShapes.chip).background(AppColors.track)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(8.dp).clip(AppShapes.chip).background(AppColors.gradientPrimary))
    }
}

// ---------------- Steps ----------------

private val stepPadding = Modifier.padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.md, bottom = Spacing.md)

/** One working set: huge target reps, the load, small steppers and the one big "Done, next". */
@Composable
private fun GuidedWork(vm: AppViewModel, live: GymLive, ei: Int, si: Int) {
    val e = live.exercises[ei]
    val ex = e.planned.exercise
    val set = e.sets[si]
    val rir = live.plan.rebaseline?.takeIf { it.active && it.stage < 3 }?.targetRir
    val last = remember(ex.id) { vm.lastTimeLine(ex.id) }
    Column(stepPadding.navigationBarsPadding().fillMaxSize()) {
        RepStepCard(
            name = ex.name, setNumber = si + 1, setCount = e.sets.size, targetReps = set.target,
            tempo = if (rir != null) "Stop with $rir+ reps in reserve" else "",
            reps = set.reps, onRepsChange = { vm.adjustSet(ei, si, dReps = it - set.reps) },
            weightKg = set.kg, onWeightChange = { vm.adjustSet(ei, si, dKg = it - set.kg) }, weightStepKg = 2.5,
            cue = if (e.sets.none { it.done }) e.planned.prescription.note else null,
            hint = last,
            onDone = { vm.completeSet(ei, si) },
            modifier = Modifier.weight(1f), pinDone = true,
            belowName = { SwapChips(vm, ei, ex) },
        )
    }
}

/**
 * One-tap swap between an exercise and its alternatives ("Standing | Seated" for the calf raises): one line of big
 * toggle chips (48 dp and up) that never grows, so the "Done, next" button stays where it is. Nothing shows when the
 * exercise has no alternatives. See [AppViewModel.swap] for what a swap keeps.
 */
@Composable
internal fun SwapChips(vm: AppViewModel, ei: Int, current: Exercise, modifier: Modifier = Modifier) {
    val options = vm.swapOptions(current)
    if (options.isEmpty()) return
    SwapChipRow(GymGuide.variants(current, options), current.id, { vm.swap(ei, it) }, modifier)
}

@Composable
internal fun SwapChipRow(variants: List<Exercise>, currentId: String, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
    ) {
        variants.forEach { v ->
            SelectChip(
                GymGuide.variantLabel(v), selected = v.id == currentId, onClick = { onPick(v.id) },
                modifier = Modifier.heightIn(min = 48.dp), accent = AppColors.info,
            )
        }
    }
}

/** "Today's session": every exercise with sets x reps and load, the time, a trim note and the prehab line, then Start. */
@Composable
internal fun GuidedOverview(vm: AppViewModel, live: GymLive) {
    val o = GymGuide.overview(live)
    OverviewContent(o, onStart = { vm.startGuidedSession() })
}

@Composable
internal fun OverviewContent(o: GymGuide.Overview, onStart: () -> Unit) {
    Column(stepPadding.navigationBarsPadding().fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Pill("TODAY'S SESSION", color = AppColors.primary, filled = true)
            Text("${o.rows.size} ${if (o.rows.size == 1) "exercise" else "exercises"}, about ${o.minutes} min", style = MaterialTheme.typography.headlineMedium, color = AppColors.textPrimary, textAlign = TextAlign.Center)
            Column(
                Modifier.fillMaxWidth().clip(AppShapes.large).background(AppColors.surfaceRaised).padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                o.rows.forEach { r ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        Text("${r.number}", style = MaterialTheme.typography.titleLarge, color = AppColors.primary)
                        Column(Modifier.weight(1f)) {
                            Text(r.name + if (r.optional) " (optional)" else "", style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary)
                            Text(r.detail, style = MaterialTheme.typography.titleSmall, color = AppColors.energy)
                        }
                    }
                }
            }
            o.trimNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted, textAlign = TextAlign.Center) }
            o.prehabLine?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = AppColors.info, textAlign = TextAlign.Center) }
        }
        BigActionButton("Start", onStart, Modifier.fillMaxWidth().heightIn(min = 96.dp))
    }
}

/** A warm-up set: nothing to log, just "Done, next". */
@Composable
private fun GuidedWarmup(vm: AppViewModel, live: GymLive, ei: Int, wi: Int) {
    val e = live.exercises[ei]
    val w = e.warmups[wi]
    Column(stepPadding.navigationBarsPadding().fillMaxSize()) {
        RepStepCard(
            name = e.planned.exercise.name, setNumber = 0, setCount = 0, badge = "WARM-UP ${wi + 1} OF ${e.warmups.size}",
            targetReps = w.reps, tempo = "Easy and smooth", reps = w.reps, onRepsChange = {}, repsStepper = false,
            weightKg = w.kg, onWeightChange = null,
            cue = "Warm-up sets are not logged. Skip them from More if you are already warm.",
            nextUp = e.sets.firstOrNull()?.let { "Then ${e.sets.size} working sets of ${GymGuide.setLine(it.kg, it.target)}" },
            onDone = { vm.completeWarmup(ei, wi) },
            modifier = Modifier.weight(1f), pinDone = true,
        )
    }
}

/** "Next exercise": the name, what is planned and why, then Start. */
@Composable
private fun GuidedIntro(vm: AppViewModel, live: GymLive, ei: Int) {
    val e = live.exercises[ei]
    val ex = e.planned.exercise
    val rx = e.planned.prescription
    val rebase = live.plan.rebaseline?.takeIf { it.active }
    val last = remember(ex.id) { vm.lastTimeLine(ex.id) }
    val missEi = live.exercises.indexOfFirst { it.askMiss }
    Column(stepPadding.navigationBarsPadding().fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Pill("NEXT EXERCISE ${ei + 1} OF ${live.exercises.size}", color = AppColors.primary, filled = true)
            Text(ex.name, style = MaterialTheme.typography.displaySmall, color = AppColors.textPrimary, textAlign = TextAlign.Center)
            SwapChips(vm, ei, ex)
            Column(
                Modifier.fillMaxWidth().clip(AppShapes.large).background(AppColors.surfaceRaised).padding(Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text("${rx.sets} sets of ${rx.reps}", style = MaterialTheme.typography.headlineMedium, color = AppColors.textPrimary)
                Text(GymGuide.loadText(rx.weightKg), style = MaterialTheme.typography.headlineSmall, color = AppColors.energy)
                if (e.warmups.isNotEmpty()) {
                    Text("Starts with ${e.warmups.size} warm-up sets", style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted)
                }
                Text("${ex.restSec} s rest between sets", style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted)
            }
            Text(rx.note, style = MaterialTheme.typography.bodyLarge, color = AppColors.textPrimary, textAlign = TextAlign.Center)
            rebase?.let { Pill(it.headline, color = AppColors.info) }
            rebase?.takeIf { it.stage < 3 }?.let {
                Text("Stop each set with ${it.targetRir}+ reps in reserve.", style = MaterialTheme.typography.bodyMedium, color = AppColors.info, textAlign = TextAlign.Center)
            }
            last?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = AppColors.info, textAlign = TextAlign.Center) }
            if (missEi >= 0) MissQuestion(live.exercises[missEi].planned.exercise.name) { vm.setMiss(missEi, it) }
        }
        BigActionButton("Start", { vm.startExercise(ei) }, Modifier.fillMaxWidth().heightIn(min = 96.dp))
    }
}

/** After "Done": an optional effort row, the countdown, "Skip rest" and what comes next. */
@Composable
private fun GuidedRest(vm: AppViewModel, live: GymLive, rest: Rest, remaining: Int, step: GymStep, onFinish: () -> Unit) {
    val set = live.exercises.getOrNull(rest.ei)?.sets?.getOrNull(rest.si)
    val missEi = live.exercises.indexOfFirst { it.askMiss }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(stepPadding).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        RestStepCard(
            secondsLeft = remaining, totalSec = rest.totalSec, onSkip = { vm.clearRest() }, nextUp = GymGuide.nextUp(live),
        ) {
            if (set != null && set.done) RirRow(set.rir, { v -> if (v != null) vm.setRir(rest.ei, rest.si, v) })
            if (missEi >= 0) MissQuestion(live.exercises[missEi].planned.exercise.name) { vm.setMiss(missEi, it) }
        }
        if (step == GymStep.Finish) BigActionButton("Finish session", onFinish, Modifier.fillMaxWidth().heightIn(min = 96.dp))
    }
}

/** Every set is done: one big Finish button (plus the miss question and the finisher note when they apply). */
@Composable
private fun GuidedFinish(vm: AppViewModel, live: GymLive, onFinish: () -> Unit, onAdd: () -> Unit) {
    val missEi = live.exercises.indexOfFirst { it.askMiss }
    val (done, _) = GymGuide.progress(live)
    Column(stepPadding.navigationBarsPadding().fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Pill("ALL DONE", color = AppColors.primary, filled = true)
            Text("That was the last set", style = MaterialTheme.typography.displaySmall, color = AppColors.textPrimary, textAlign = TextAlign.Center)
            Text(
                if (done == 0) "Nothing is logged yet." else "$done ${if (done == 1) "set" else "sets"} ready to save. Nice work.",
                style = MaterialTheme.typography.titleMedium, color = AppColors.textMuted, textAlign = TextAlign.Center,
            )
            if (missEi >= 0) MissQuestion(live.exercises[missEi].planned.exercise.name) { vm.setMiss(missEi, it) }
            if (live.prehabMinutes > 0) {
                SectionCard(title = "Next: hip prehab (about ${live.prehabMinutes} min, optional)") {
                    Text(PREHAB_BLURB, style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted)
                }
            }
            if (live.finisher.isNotEmpty()) {
                SectionCard(title = "Station finisher next (optional)") {
                    Text(live.finisher.first(), style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted)
                    live.finisher.drop(1).forEach { Text(it, style = MaterialTheme.typography.titleSmall, color = AppColors.textPrimary) }
                }
            }
            SecondaryButton("Add an exercise", onAdd, Modifier.fillMaxWidth(), accent = AppColors.info)
            SecondaryButton("Overview (all sets)", { vm.setGymGuided(false) }, Modifier.fillMaxWidth())
        }
        BigActionButton("Finish session", onFinish, Modifier.fillMaxWidth().heightIn(min = 96.dp))
    }
}

/**
 * "A few reps were missed. How did it feel?" as two large buttons. Never blocks: Start, Skip rest and Finish all work
 * without answering it. Shared by the list and the guided screens.
 */
@Composable
internal fun MissQuestion(exerciseName: String?, onReason: (MissReason) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(AppShapes.large).background(AppColors.surfaceRaised).padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            if (exerciseName == null) "A few reps were missed. How did it feel?" else "A few $exerciseName reps were missed. How did it feel?",
            style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            SecondaryButton("Rough day", { onReason(MissReason.ROUGH_DAY) }, Modifier.weight(1f).heightIn(min = 80.dp), accent = AppColors.info)
            SecondaryButton("At my limit", { onReason(MissReason.AT_LIMIT) }, Modifier.weight(1f).heightIn(min = 80.dp), accent = AppColors.energy)
        }
    }
}

// ---------------- Previews ----------------

@Preview(name = "Missed reps question", widthDp = 380, heightDp = 260)
@Composable
private fun MissQuestionPreview() = TrainingTheme {
    Column(Modifier.background(AppColors.background).padding(Spacing.screen)) { MissQuestion("Squat") {} }
}

@Preview(name = "Guided progress bar", widthDp = 380, heightDp = 60)
@Composable
private fun GuidedProgressPreview() = TrainingTheme {
    Column(Modifier.background(AppColors.background).padding(Spacing.screen)) {
        Text("Squat + RDL", style = AppType.eyebrow, color = AppColors.textMuted)
        Spacer(Modifier.height(Spacing.xs))
        ProgressBar(0.4f)
    }
}
