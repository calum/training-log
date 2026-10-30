package com.calum.traininglog

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.calum.traininglog.core.MissReason
import com.calum.traininglog.core.Role
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.AppShapes
import com.calum.traininglog.ui.AppType
import com.calum.traininglog.ui.BigActionButton
import com.calum.traininglog.ui.ConfettiBurst
import com.calum.traininglog.ui.Pill
import com.calum.traininglog.ui.RestTimerBar
import com.calum.traininglog.ui.SecondaryButton
import com.calum.traininglog.ui.SectionCard
import com.calum.traininglog.ui.SelectChip
import com.calum.traininglog.ui.BigNumberStepper
import com.calum.traininglog.ui.DoneToggle
import com.calum.traininglog.ui.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------- Gym ----------------

@Composable
fun GymScreen(vm: AppViewModel) {
    val g by vm.gym.collectAsState()
    val live = g ?: run { vm.home(); return }
    KeepScreenOn()
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }

    var flash by remember { mutableStateOf(false) }
    var remaining by remember { mutableIntStateOf(0) }
    LaunchedEffect(live.rest) {
        val rest = live.rest ?: run { remaining = 0; return@LaunchedEffect }
        while (true) {
            val left = ((rest.endsAt - SystemClock.elapsedRealtime()) / 1000).toInt() + 1
            remaining = left.coerceAtLeast(0)
            if (left <= 0) break
            delay(250)
        }
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
        flash = true
        delay(800)
        flash = false
        vm.clearRest()
    }

    // Confetti: small burst each time another exercise is fully done, big one on finishing.
    val doneExercises = live.exercises.count { e -> e.sets.isNotEmpty() && e.sets.all { it.done } }
    var seenDone by remember { mutableIntStateOf(doneExercises) }
    var smallBurst by remember { mutableIntStateOf(0) }
    var bigBurst by remember { mutableIntStateOf(0) }
    LaunchedEffect(doneExercises) {
        if (doneExercises > seenDone) smallBurst++
        seenDone = doneExercises
    }
    val scope = rememberCoroutineScope()

    val totalSets = live.exercises.sumOf { it.sets.size }
    val doneSets = live.exercises.sumOf { e -> e.sets.count { it.done } }
    val progress = if (totalSets == 0) 0f else doneSets / totalSets.toFloat()
    val flashBg by animateColorAsState(
        if (flash) AppColors.primary.copy(alpha = 0.22f) else AppColors.background, tween(200), label = "flashBg",
    )
    val rest = live.rest

    Box(Modifier.fillMaxSize().background(flashBg)) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).statusBarsPadding().padding(Spacing.screen),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                GymHeader(live.plan.dayLabel, live.plan.deload, doneSets, totalSets, progress)
                if (live.plan.dropped.isNotEmpty()) {
                    Text(
                        "Trimmed to fit your time: ${live.plan.dropped.joinToString { it.name }}",
                        style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
                    )
                }
                live.exercises.forEachIndexed { ei, e -> ExerciseCard(vm, ei, e) }
                BigActionButton("Finish session", onClick = {
                    bigBurst++
                    scope.launch { delay(900); vm.finishGym() }
                })
                SecondaryButton("Discard", onClick = { vm.home() }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(Spacing.lg))
            }
            if (rest != null) {
                RestTimerBar(
                    remainingSec = remaining, totalSec = rest.totalSec,
                    modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.sm).navigationBarsPadding(),
                    onSkip = { vm.clearRest() },
                )
            }
        }
        ConfettiBurst(smallBurst, Modifier.fillMaxSize(), particleCount = 60)
        ConfettiBurst(bigBurst, Modifier.fillMaxSize(), particleCount = 220, durationMillis = 2400)
    }
}

@Composable
private fun GymHeader(dayLabel: String, deload: Boolean, done: Int, total: Int, progress: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text("Gym $dayLabel", style = MaterialTheme.typography.headlineLarge, color = AppColors.textPrimary, modifier = Modifier.weight(1f, fill = false))
            if (deload) Pill("Deload week", color = AppColors.info)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Box(Modifier.weight(1f).height(12.dp).clip(AppShapes.chip).background(AppColors.track)) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(12.dp).clip(AppShapes.chip).background(AppColors.gradientPrimary))
            }
            Text("$done / $total sets", style = MaterialTheme.typography.labelLarge, color = AppColors.textMuted)
        }
    }
}

@Composable
private fun ExerciseCard(vm: AppViewModel, ei: Int, e: LiveExercise) {
    var showSwap by remember { mutableStateOf(false) }
    val ex = e.planned.exercise
    val allDone = e.sets.isNotEmpty() && e.sets.all { it.done }
    SectionCard(
        title = ex.name,
        action = {
            if (allDone) Pill("Done", filled = true)
            else if (ex.role == Role.ACCESSORY) SelectChip(if (showSwap) "Close" else "Swap", selected = showSwap, onClick = { showSwap = !showSwap })
        },
    ) {
        Text(e.planned.prescription.note, style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted)
        if (showSwap) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                vm.swapOptions(ex).forEach { alt ->
                    SelectChip(alt.name, selected = false, onClick = { vm.swap(ei, alt.id); showSwap = false }, accent = AppColors.info)
                }
            }
        }
        if (e.warmups.isNotEmpty()) {
            Text("WARM-UP", style = AppType.eyebrow, color = AppColors.textMuted)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                e.warmups.forEachIndexed { wi, w ->
                    SelectChip("${fmt(w.kg)} kg × ${w.reps}", selected = w.done, onClick = { vm.toggleWarmup(ei, wi) })
                }
            }
        }
        e.sets.forEachIndexed { si, set ->
            GymSetCard(vm, ei, si, set)
        }
        if (e.askMiss) {
            Text("A few reps were missed. How did it feel?", style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                SecondaryButton("Rough day", { vm.setMiss(ei, MissReason.ROUGH_DAY) }, Modifier.weight(1f), accent = AppColors.info)
                SecondaryButton("At my limit", { vm.setMiss(ei, MissReason.AT_LIMIT) }, Modifier.weight(1f), accent = AppColors.energy)
            }
        }
    }
}

/** One set: number badge + giant Done toggle, big hold-to-repeat weight and reps steppers, RIR chips once done. */
@Composable
private fun GymSetCard(vm: AppViewModel, ei: Int, si: Int, s: LiveSet) {
    val bg by animateColorAsState(
        if (s.done) AppColors.primary.copy(alpha = 0.16f) else AppColors.surfaceRaised, tween(350), label = "setBg",
    )
    Column(
        Modifier.fillMaxWidth().clip(AppShapes.medium).background(bg).padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (s.done) AppColors.primary.copy(alpha = 0.3f) else AppColors.surfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text("${si + 1}", style = MaterialTheme.typography.titleMedium, color = if (s.done) AppColors.primary else AppColors.textMuted)
            }
            Text(
                if (s.done) "Nice. Set done" else "Set ${si + 1} of target ${s.target} reps",
                style = MaterialTheme.typography.titleSmall, color = if (s.done) AppColors.primary else AppColors.textMuted,
                modifier = Modifier.weight(1f),
            )
            DoneToggle(s.done, onToggle = { vm.doneSet(ei, si) })
        }
        BigNumberStepper(
            value = fmt(s.kg), unit = "kg", label = "Weight",
            onMinus = { vm.adjustSet(ei, si, dKg = -2.5) }, onPlus = { vm.adjustSet(ei, si, dKg = 2.5) },
        )
        BigNumberStepper(
            value = "${s.reps}", unit = "reps", label = "Reps",
            onMinus = { vm.adjustSet(ei, si, dReps = -1) }, onPlus = { vm.adjustSet(ei, si, dReps = 1) },
        )
        if (s.done) {
            Text("HOW MANY REPS LEFT IN THE TANK?", style = AppType.eyebrow, color = AppColors.textMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                listOf(0, 1, 2, 3).forEach { r ->
                    SelectChip(
                        if (r == 3) "3+" else "$r", selected = s.rir == r, onClick = { vm.setRir(ei, si, r) },
                        modifier = Modifier.weight(1f), accent = AppColors.energy,
                    )
                }
            }
        }
    }
}
