package com.calum.traininglog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.calum.traininglog.core.CalfMode
import com.calum.traininglog.core.CoreEvent
import com.calum.traininglog.core.CoreKind
import com.calum.traininglog.core.CoreSide
import com.calum.traininglog.core.CueType
import com.calum.traininglog.core.ExerciseLibrary
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.KneeCare
import com.calum.traininglog.core.KneeCareRoutine
import com.calum.traininglog.core.KneeGate
import com.calum.traininglog.core.LoggedSet
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.AppShapes
import com.calum.traininglog.ui.AppType
import com.calum.traininglog.ui.BigActionButton
import com.calum.traininglog.ui.ConfettiBurst
import com.calum.traininglog.ui.RepStepCard
import com.calum.traininglog.ui.RestStepCard
import com.calum.traininglog.ui.RirRow
import com.calum.traininglog.ui.SecondaryButton
import com.calum.traininglog.ui.SectionCard
import com.calum.traininglog.ui.SelectChip
import com.calum.traininglog.ui.Spacing
import com.calum.traininglog.ui.StatTile
import kotlinx.coroutines.delay

/*
 * Guided knee care: the same mechanics as the mat session (a monotonic tick, beeps, pause when the app is left), with one
 * difference: the calf sets are rep-based. They show the target reps huge and wait for "Done, next" (see RepStepCard in
 * ui/RepStep.kt); only the rest between sets counts down. The session is saved the moment it ends (see
 * AppViewModel.autosaveKneeCare); the finish card only edits that record.
 */

@Composable
fun KneeCareScreen(vm: AppViewModel) {
    val ui by vm.kneeCare.ui.collectAsState()
    when (ui.phase) {
        KneeCarePhase.SETUP -> KneeCareSetupContent(
            ui,
            onMode = { vm.kneeCareSetup(mode = it) },
            onKnee = { if (it == null) vm.kneeCareSetup(clearKnee = true) else vm.kneeCareSetup(knee = it) },
            onShuffle = { vm.kneeCareSetup(reshuffle = true) },
            onCalfOnly = { vm.kneeCareSetup(calfOnly = it) },
            onStart = { vm.kneeCare.start() },
            onBack = { vm.kneeCareBack() },
        )
        KneeCarePhase.RUNNING -> KneeCarePlayer(vm, ui)
        KneeCarePhase.DONE -> KneeCareDoneContent(
            ui, vm.kneeCare.doneCalfSets(ui),
            onFeeling = { vm.kneeCare.setFeeling(it) }, onKneeAfter = { vm.kneeCare.setKneeAfter(it) }, onFinish = { vm.saveKneeCare() },
        )
    }
}

@Composable
private fun KneeCarePlayer(vm: AppViewModel, ui: KneeCareUi) {
    ui.run ?: return
    KeepScreenOn()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val cues = remember { CuePlayer(ctx.applicationContext, scope) }
    DisposableEffect(Unit) { onDispose { cues.release() } }

    fun play(e: CoreEvent?) = when (e) {
        CoreEvent.PRE_WARNING -> cues.play(CueType.PRE_WARNING)
        CoreEvent.CHANGE -> cues.play(CueType.CHANGE)
        CoreEvent.DONE -> cues.play(CueType.DONE)
        null -> Unit
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev -> if (ev == Lifecycle.Event.ON_STOP) vm.kneeCare.pauseAway() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(Unit) {
        var last = android.os.SystemClock.elapsedRealtime()
        while (true) {
            delay(100)
            val now = android.os.SystemClock.elapsedRealtime()
            val d = (now - last).coerceIn(0, 1000)
            last = now
            play(vm.kneeCare.tick(d))
        }
    }

    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(ui.changes) {
        if (ui.changes > 0) { flash = true; delay(500); flash = false }
    }

    KneeCarePlayerBody(
        ui, flash,
        onTogglePause = { vm.kneeCare.togglePause() },
        onSkip = { play(vm.kneeCare.skip()) },
        onEnd = { vm.kneeCare.end() },
        onDone = { play(vm.kneeCare.next()) },
        onReps = { r -> ui.run?.let { run -> ui.session?.calfSetAt(run.index)?.let { i -> vm.kneeCare.setCalf(i) { e -> e.copy(reps = r) } } } },
        onWeight = { kg -> ui.run?.let { run -> ui.session?.calfSetAt(run.index)?.let { i -> vm.kneeCare.setCalfWeight(i, kg) } } },
        onRir = { rir -> ui.run?.let { run -> ui.session?.calfSetAt(run.index)?.let { i -> vm.kneeCare.setCalf(i) { e -> e.copy(rir = rir) } } } },
    )
}

/** Weight steps: 1 kg while the load is light, 2.5 kg once it is heavier (matches the progression). */
internal fun calfStepKg(kg: Double?) = if ((kg ?: 0.0) < KneeCare.LIGHT_LOAD_KG) 1.0 else 2.5

@Composable
internal fun KneeCarePlayerBody(
    ui: KneeCareUi, flash: Boolean,
    onTogglePause: () -> Unit, onSkip: () -> Unit, onEnd: () -> Unit, onDone: () -> Unit,
    onReps: (Int) -> Unit, onWeight: (Double) -> Unit, onRir: (Int?) -> Unit,
) {
    val run = ui.run ?: return
    val s = ui.session ?: return
    val step = run.current ?: return
    val setIdx = s.calfSetAt(run.index)
    val entry = setIdx?.let { ui.calf.getOrNull(it) }
    val calfRelated = step.exerciseId == ExerciseLibrary.CALF_HOME_ID || step.exerciseId == KneeCareRoutine.CALF_WARMUP_ID
    when {
        s.isRepStep(run.index) -> Column(
            Modifier.fillMaxSize().background(AppColors.background).statusBarsPadding().padding(Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            ControlRow(run.paused, onTogglePause, onSkip, onEnd, skipLabel = "Skip set")
            if (run.paused) Text("Paused. Take your time, then press Resume.", color = AppColors.energy, style = MaterialTheme.typography.bodyMedium)
            if (setIdx != null && entry != null) {
                RepStepCard(
                    name = step.name, setNumber = setIdx + 1, setCount = s.calfSets, targetReps = s.calfReps, tempo = s.calfMode.tempo,
                    reps = entry.reps, onRepsChange = onReps, weightKg = entry.kg, onWeightChange = onWeight, weightStepKg = calfStepKg(entry.kg),
                    cue = step.cue, hint = s.lastTime, onDone = onDone, modifier = Modifier.weight(1f), pinDone = true,
                )
            } else {
                // The calf warm-up set: easy reps, no load to log.
                RepStepCard(
                    name = step.name, setNumber = 0, setCount = 0, targetReps = step.reps ?: 10, tempo = "2 s up, 2 s down",
                    reps = step.reps ?: 10, onRepsChange = {}, weightKg = null, onWeightChange = null, cue = step.cue, onDone = onDone,
                    doneLabel = "Done, next", modifier = Modifier.weight(1f), pinDone = true,
                )
            }
        }
        step.kind == CoreKind.REST && calfRelated -> Column(
            Modifier.fillMaxSize().background(AppColors.background).statusBarsPadding().verticalScroll(rememberScrollState()).padding(Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            RestStepCard(
                secondsLeft = run.secondsLeft, totalSec = step.seconds, onSkip = onSkip, nextUp = step.nextName, paused = run.paused,
            ) {
                if (entry != null) RirRow(entry.rir, onRir)
            }
            ControlRow(run.paused, onTogglePause, onSkip, onEnd, skipLabel = null)
        }
        else -> CorePlayerBody(run, flash, onTogglePause, onSkip, onEnd)
    }
}

@Composable
private fun ControlRow(paused: Boolean, onTogglePause: () -> Unit, onSkip: () -> Unit, onEnd: () -> Unit, skipLabel: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SecondaryButton(if (paused) "Resume" else "Pause", onTogglePause, Modifier.weight(1f))
        if (skipLabel != null) SecondaryButton(skipLabel, onSkip, Modifier.weight(1f))
        SecondaryButton("End", onEnd, Modifier.weight(1f), accent = AppColors.textMuted)
    }
}

// ---------------- Setup ----------------

@Composable
internal fun KneeCareSetupContent(
    ui: KneeCareUi,
    onMode: (CalfMode) -> Unit,
    onKnee: (Int?) -> Unit,
    onShuffle: () -> Unit,
    onCalfOnly: (Boolean) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    val s = ui.session
    val minutes = s?.minutes ?: ui.minutes
    Column(
        Modifier.fillMaxSize().background(AppColors.background).verticalScroll(rememberScrollState())
            .statusBarsPadding().padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text("Knee care", style = MaterialTheme.typography.headlineLarge, color = AppColors.textPrimary)
            Text(
                if (ui.calfOnly) "Just calf raises, about $minutes minutes" else "$minutes minutes: hips, calves, gentle stretches. No jumping.",
                style = MaterialTheme.typography.titleMedium, color = AppColors.textMuted,
            )
        }
        Text(
            "Do it at the end of any day, after other exercise, or on a rest day. At least twice a week is the aim, and more is fine.",
            style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
        )

        Text("CALF RAISES", style = AppType.eyebrow, color = AppColors.textMuted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SelectChip("Heavy", ui.mode == CalfMode.HEAVY, { onMode(CalfMode.HEAVY) }, Modifier.weight(1f))
            SelectChip("Higher reps", ui.mode == CalfMode.STAMINA, { onMode(CalfMode.STAMINA) }, Modifier.weight(1f))
        }
        SectionCard {
            val sets = s?.calfSets ?: 3
            val load = s?.calfStartKg?.let { if (it <= 0.0) "bodyweight" else "${if (it % 1.0 == 0.0) it.toInt() else it} kg" }
            Text(
                "${ui.mode.label}: $sets sets of ${ui.mode.range}, ${ui.mode.tempo}",
                style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary,
            )
            Text(
                if (load != null) "Today's target: ${s?.calfReps} reps at $load." else "Pick your load on the first set (a dumbbell, kettlebell or loaded backpack).",
                style = MaterialTheme.typography.bodyMedium, color = AppColors.energy,
            )
            s?.lastTime?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = AppColors.info) }
            Text(
                if (ui.mode == CalfMode.HEAVY) "Heavy, slow calf raises are a sensible, well-tolerated choice, and how your own knee responds matters most. When every set reaches ${ui.mode.repsHigh} reps, add weight."
                else "Higher reps build stamina. When every set reaches ${ui.mode.repsHigh} reps, add a little weight.",
                style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
            )
        }

        Text("HOW'S THE KNEE RIGHT NOW? 0 (FINE) TO 10", style = AppType.eyebrow, color = AppColors.textMuted)
        ChipGrid((0..10).map<Int, Int?> { it } + null, 6, { ui.knee == it }, { it?.toString() ?: "Skip" }) { onKnee(it) }
        s?.physioLine?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.info, modifier = Modifier.fillMaxWidth().clip(AppShapes.medium).background(AppColors.info.copy(alpha = 0.12f)).padding(Spacing.md))
        }
        if (s?.gate == KneeGate.GENTLE_ONLY) {
            Text("With the knee this sore, today is gentle mobility only: no strengthening.", style = MaterialTheme.typography.bodyMedium, color = AppColors.textPrimary)
        } else if (s?.gate == KneeGate.NO_STEP_DOWN) {
            Text("The step-down is left out today.", style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted)
        }

        if (s != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                StatTile("Time", "$minutes", Modifier.weight(1f), unit = "min", accent = AppColors.primary, compact = true)
                StatTile("Moves", "${s.names.size}", Modifier.weight(1f), accent = AppColors.reward, compact = true)
                StatTile("Calf sets", "${s.calfSets}", Modifier.weight(1f), accent = AppColors.info, compact = true)
            }
        }
        BigActionButton("Start", onStart, enabled = s != null)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            if (!ui.calfOnly) SecondaryButton("Different mix", onShuffle, Modifier.weight(1f))
            SecondaryButton("Back", onBack, Modifier.weight(1f), accent = AppColors.textMuted)
        }
        if (ui.calfOnly) SecondaryButton("Full knee care instead (15 min)", { onCalfOnly(false) }, Modifier.fillMaxWidth())
        else SecondaryButton("Just calf raises (8 min)", { onCalfOnly(true) }, Modifier.fillMaxWidth(), accent = AppColors.energy)
        Text(
            "You get a short beep when each rest ends and at each timed switch. Calf sets are at your own pace: do the reps, then press Done. " +
                "The timer runs inside the app: if you leave it, the session pauses and waits for you.",
            style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
        )
        if (s != null) {
            Text("WHAT'S IN IT", style = AppType.eyebrow, color = AppColors.textMuted)
            val groups = listOf(
                "Warm-up" to s.steps.filter { it.kind == CoreKind.WARMUP },
                "Hips and glutes" to s.steps.filter { it.kind == CoreKind.WORK && !KneeCareRoutine.isRepStep(it) },
                "Calf raises" to s.steps.filter { it.kind == CoreKind.WORK && KneeCareRoutine.isRepStep(it) },
                "Cool-down" to s.steps.filter { it.kind == CoreKind.COOLDOWN },
            ).filter { it.second.isNotEmpty() }
            groups.forEach { (title, steps) ->
                SectionCard(title = title) {
                    val seen = mutableSetOf<String>()
                    steps.filter { seen.add(it.name) }.forEach { st ->
                        val n = steps.count { it.name == st.name }
                        val rep = KneeCareRoutine.isRepStep(st)
                        StepLine(
                            if (rep) "${st.reps}" else "${st.seconds}s", st.name,
                            when {
                                rep && st.exerciseId == ExerciseLibrary.CALF_HOME_ID -> "x $n sets"
                                st.side != null -> "each side"
                                else -> null
                            },
                        )
                    }
                }
            }
        }
        Text(
            "The hip work is the part with the most support for ITB trouble; calf raises are sensible general strength for the lower leg. " +
                "No foam rolling on the sore spot. Not medical advice: if pain persists, see your physio.",
            style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
        )
        Spacer(Modifier.height(Spacing.lg))
    }
}

// ---------------- Done ----------------

@Composable
internal fun KneeCareDoneContent(
    ui: KneeCareUi, calfSets: List<LoggedSet>,
    onFeeling: (Feeling) -> Unit, onKneeAfter: (Int?) -> Unit, onFinish: () -> Unit,
) {
    var burst by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { burst = 1 }
    val minutes = ((ui.run?.completedSec ?: 0) + 30) / 60
    BoxWithConstraints(Modifier.fillMaxSize().background(AppColors.background)) {
        val minH = maxHeight
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(Spacing.screen).heightIn(min = minH - 56.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg, Alignment.CenterVertically),
        ) {
            Text("Nice work", style = MaterialTheme.typography.displaySmall, color = AppColors.textPrimary)
            Text(
                if (minutes >= 1) "$minutes min of knee care done and saved." else "Under a minute, so nothing will be logged.",
                style = MaterialTheme.typography.titleMedium, color = AppColors.textMuted,
            )
            if (minutes >= 1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    StatTile("Time", "$minutes", Modifier.weight(1f), unit = "min", compact = true)
                    StatTile("Calf sets", "${calfSets.size}", Modifier.weight(1f), accent = AppColors.reward, compact = true)
                    val top = calfSets.maxOfOrNull { it.weightKg }
                    StatTile("Top load", if (top == null || top <= 0.0) "BW" else "${if (top % 1.0 == 0.0) top.toInt() else top}", Modifier.weight(1f), unit = if (top != null && top > 0.0) "kg" else "", accent = AppColors.energy, compact = true)
                }
                if (calfSets.isNotEmpty()) {
                    Text(
                        "Calf raises: " + calfSets.joinToString(", ") { it.reps.toString() } + " reps. Your next target is worked out from this.",
                        style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
                    )
                }
                Text("How did that feel? (optional)", style = MaterialTheme.typography.headlineSmall, color = AppColors.textPrimary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    listOf(Feeling.GOOD to "Good", Feeling.OKAY to "Okay", Feeling.ROUGH to "Rough").forEach { (f, label) ->
                        SelectChip(label, ui.feeling == f, { onFeeling(f) }, Modifier.weight(1f))
                    }
                }
                Text("KNEE NOW, 0 (FINE) TO 10 (OPTIONAL)", style = AppType.eyebrow, color = AppColors.textMuted)
                ChipGrid((0..10).map<Int, Int?> { it } + null, 6, { ui.kneeAfter == it }, { it?.toString() ?: "Skip" }) { onKneeAfter(it) }
                if ((ui.kneeAfter ?: 0) >= KneeCareRoutine.STEP_DOWN_OFF_KNEE) {
                    Text(KneeCareRoutine.PHYSIO_LINE, style = MaterialTheme.typography.bodyMedium, color = AppColors.info)
                }
            }
            BigActionButton("Done", onFinish)
        }
        ConfettiBurst(burst, Modifier.fillMaxSize(), particleCount = 80)
    }
}
