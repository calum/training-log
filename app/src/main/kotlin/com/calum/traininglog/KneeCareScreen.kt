package com.calum.traininglog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
import com.calum.traininglog.core.CalfLadder
import com.calum.traininglog.core.CalfMode
import com.calum.traininglog.core.Effort
import com.calum.traininglog.core.KneeCareKit
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
import com.calum.traininglog.ui.FeelRow
import com.calum.traininglog.ui.Pill
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
            onKit = { vm.setKneeKit(it) },
            onGear = { vm.kneeCare.toggleGear(it) },
            onLevel = { vm.kneeCareSetup(levelStep = it) },
        )
        KneeCarePhase.GET_READY -> KneeCareReadyContent(ui, onReady = { vm.kneeCare.ready() }, onBack = { vm.kneeCare.backToSetup() })
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
        onEffort = { eff -> ui.run?.let { run -> ui.session?.calfSetAt(run.index)?.let { i -> vm.kneeCare.rate(i, eff) } } },
    )
}

/** Weight steps: 1 kg while the load is light, 2.5 kg once it is heavier (matches the progression). */
internal fun calfStepKg(kg: Double?) = if ((kg ?: 0.0) < KneeCare.LIGHT_LOAD_KG) 1.0 else 2.5

@Composable
internal fun KneeCarePlayerBody(
    ui: KneeCareUi, flash: Boolean,
    onTogglePause: () -> Unit, onSkip: () -> Unit, onEnd: () -> Unit, onDone: () -> Unit,
    onReps: (Int) -> Unit, onWeight: (Double) -> Unit, onEffort: (Effort?) -> Unit,
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
                val spec = CalfLadder.spec(entry.level, s.kit)
                RepStepCard(
                    name = spec.title(s.calfVariant), setNumber = setIdx + 1, setCount = s.calfSets,
                    targetReps = entry.targetReps.takeIf { it > 0 } ?: s.calfReps, tempo = spec.tempo,
                    reps = entry.reps, onRepsChange = onReps, weightKg = entry.kg, onWeightChange = onWeight, weightStepKg = calfStepKg(entry.kg),
                    cue = spec.cue(), hint = s.lastTime, onDone = onDone, modifier = Modifier.weight(1f), pinDone = true,
                    levelLine = spec.levelLine, effort = entry.effort, onEffort = onEffort,
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
            val felt = setIdx?.let { i -> ui.calf.take(i + 1).lastOrNull { it.effort != null }?.effort }
            RestStepCard(
                secondsLeft = run.secondsLeft, totalSec = step.seconds, onSkip = onSkip, nextUp = step.nextName, paused = run.paused,
                title = when (felt) { Effort.TOO_EASY -> "Short rest: that felt easy"; Effort.TOO_HARD -> "A little longer: that felt hard"; else -> "Rest" },
            ) {
                if (entry != null) FeelRow(entry.effort, onEffort)
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
    onKit: (KneeCareKit) -> Unit = {},
    onGear: (String) -> Unit = {},
    onLevel: (Int) -> Unit = {},
) {
    val s = ui.session
    val kit = s?.kit ?: KneeCareKit()
    val minutes = s?.minutes ?: ui.minutes
    Column(
        Modifier.fillMaxSize().background(AppColors.background).verticalScroll(rememberScrollState())
            .statusBarsPadding().padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text("Knee care", style = MaterialTheme.typography.headlineLarge, color = AppColors.textPrimary)
            Text(
                when {
                    ui.hipOnly -> "Hip prehab, about $minutes minutes: hips and glutes, then two stretches. No calf work, no jumping."
                    ui.calfOnly -> "Just calf raises, about $minutes minutes"
                    else -> "$minutes minutes: hips, calves, gentle stretches. No jumping."
                },
                style = MaterialTheme.typography.titleMedium, color = AppColors.textMuted,
            )
        }
        Text(
            "Do it at the end of any day, after other exercise, or on a rest day. At least twice a week is the aim, and more is fine.",
            style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
        )

        if (s != null) {
            val gear = s.gear
            if (gear.isNotEmpty()) {
                SectionCard(title = "You'll need") {
                    Text("Have these to hand before you start, so nothing has to be fetched mid-routine. Ticking is just a memory aid.", style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
                    gear.forEach { g -> GearRow(g.name, g.forStep, g.optional, g.name in ui.checked) { onGear(g.name) } }
                }
            }
        }
        KitRow(kit, onKit)

        if (!ui.hipOnly) {
        Text("CALF RAISES", style = AppType.eyebrow, color = AppColors.textMuted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SelectChip("Heavy", ui.mode == CalfMode.HEAVY, { onMode(CalfMode.HEAVY) }, Modifier.weight(1f))
            SelectChip("Higher reps", ui.mode == CalfMode.STAMINA, { onMode(CalfMode.STAMINA) }, Modifier.weight(1f))
        }
        SectionCard {
            val spec = s?.calfSpec ?: CalfLadder.spec(1, kit)
            val load = s?.calfStartKg?.let { if (it <= 0.0) "bodyweight" else "${if (it % 1.0 == 0.0) it.toInt() else it} kg" }
            Text(spec.levelLine, style = MaterialTheme.typography.titleMedium, color = AppColors.reward)
            if (s?.levelMoved == true) Text("You moved up a level.", style = MaterialTheme.typography.titleSmall, color = AppColors.primary)
            Text(
                "${s?.calfSets ?: 3} sets of ${if (ui.mode == CalfMode.STAMINA) "${s?.calfReps ?: spec.repsHigh}" else "${spec.repsLow}-${spec.repsHigh}"} reps. ${spec.tempo}.",
                style = MaterialTheme.typography.bodyLarge, color = AppColors.textPrimary,
            )
            if (load != null) Text("Today's target: ${s?.calfReps} reps at $load.", style = MaterialTheme.typography.bodyMedium, color = AppColors.energy)
            s?.lastTime?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = AppColors.info) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SecondaryButton("Easier", { onLevel(-1) }, Modifier.weight(1f), enabled = spec.level > 1)
                SecondaryButton("Harder", { onLevel(1) }, Modifier.weight(1f), enabled = spec.level < CalfLadder.LEVELS)
            }
            Text(
                com.calum.traininglog.core.KneeCare.HOME_NOTE + " When every set reaches the top reps with 2 left in the tank, or you tap Too easy, you move up a level.",
                style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
            )
        }
        }

        Text("HOW'S THE KNEE RIGHT NOW? 0 (FINE) TO 10", style = AppType.eyebrow, color = AppColors.textMuted)
        ChipGrid((0..10).map<Int, Int?> { it } + null, 6, { ui.knee == it }, { it?.toString() ?: "Skip" }) { onKnee(it) }
        s?.physioLine?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.info, modifier = Modifier.fillMaxWidth().clip(AppShapes.medium).background(AppColors.info.copy(alpha = 0.12f)).padding(Spacing.md))
        }
        if (s?.gate == KneeGate.GENTLE_ONLY) {
            Text("With the knee this sore, today is gentle mobility only: no strengthening.", style = MaterialTheme.typography.bodyMedium, color = AppColors.textPrimary)
        } else if (s?.gate == KneeGate.NO_STEP_DOWN) {
            Text("Single-leg squats and step-downs are left out today.", style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted)
        }

        if (s != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                StatTile("Time", "$minutes", Modifier.weight(1f), unit = "min", accent = AppColors.primary, compact = true)
                StatTile("Moves", "${s.names.size}", Modifier.weight(1f), accent = AppColors.reward, compact = true)
                if (!ui.hipOnly) StatTile("Calf sets", "${s.calfSets}", Modifier.weight(1f), accent = AppColors.info, compact = true)
            }
        }
        BigActionButton("Start", onStart, enabled = s != null)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            if (!ui.calfOnly) SecondaryButton("Different mix", onShuffle, Modifier.weight(1f))
            SecondaryButton(if (ui.hipOnly) "Skip prehab" else "Back", onBack, Modifier.weight(1f), accent = AppColors.textMuted)
        }
        if (!ui.hipOnly) {
            if (ui.calfOnly) SecondaryButton("Full knee care instead", { onCalfOnly(false) }, Modifier.fillMaxWidth())
            else SecondaryButton("Just calf raises", { onCalfOnly(true) }, Modifier.fillMaxWidth(), accent = AppColors.energy)
        }
        Text(
            "You get a short beep when each rest ends and at each timed switch. " +
                (if (ui.hipOnly) "" else "Calf sets are at your own pace: do the reps, then press Done. ") +
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

@Composable
private fun GearRow(name: String, forStep: String, optional: Boolean, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox, onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(name + if (optional) " (optional)" else "", style = MaterialTheme.typography.titleSmall, color = AppColors.textPrimary)
            Text(forStep, style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
        }
    }
}

/** "My home kit": toggle chips for step, band, chair and mat, and a kettlebell weight stepper in 1 kg steps (none allowed). */
@Composable
private fun KitRow(kit: KneeCareKit, onKit: (KneeCareKit) -> Unit) {
    SectionCard(title = "My home kit") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SelectChip("Step", kit.hasStep, { onKit(kit.copy(hasStep = !kit.hasStep)) }, Modifier.weight(1f))
            SelectChip("Band", kit.hasBand, { onKit(kit.copy(hasBand = !kit.hasBand)) }, Modifier.weight(1f))
            SelectChip("Chair", kit.hasChair, { onKit(kit.copy(hasChair = !kit.hasChair)) }, Modifier.weight(1f))
            SelectChip("Mat", kit.hasMat, { onKit(kit.copy(hasMat = !kit.hasMat)) }, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text("Kettlebell", style = MaterialTheme.typography.titleSmall, color = AppColors.textPrimary, modifier = Modifier.weight(1f))
            KitStep("-", "Lighter kettlebell") { onKit(kit.withKettlebellStep(-1)) }
            Text(kit.kettlebellKg?.let { "${it.toInt()} kg" } ?: "None", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = AppColors.textPrimary, modifier = Modifier.padding(horizontal = Spacing.xs))
            KitStep("+", "Heavier kettlebell") { onKit(kit.withKettlebellStep(1)) }
        }
        Text("The routine only uses what is ticked here.", style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
    }
}

@Composable
private fun KitStep(symbol: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(AppColors.surface).border(1.5.dp, AppColors.outline, CircleShape)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(symbol, style = MaterialTheme.typography.titleLarge, color = AppColors.textPrimary) }
}

// ---------------- Get ready ----------------

/** The step between Start and the warm-up: grab the gear now, then press Ready. Nothing runs until then. */
@Composable
internal fun KneeCareReadyContent(ui: KneeCareUi, onReady: () -> Unit, onBack: () -> Unit) {
    val gear = ui.session?.gear.orEmpty()
    Column(
        Modifier.fillMaxSize().background(AppColors.background).statusBarsPadding().padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Pill("GET READY", color = AppColors.primary, filled = true)
        Text("Grab these now", style = MaterialTheme.typography.headlineLarge, color = AppColors.textPrimary)
        Text("So you do not have to stop and fetch anything. The warm-up starts when you press Ready.", style = MaterialTheme.typography.bodyLarge, color = AppColors.textMuted)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            gear.forEach { g ->
                Column(Modifier.fillMaxWidth().clip(AppShapes.medium).background(AppColors.surfaceRaised).padding(Spacing.md)) {
                    Text(g.name + if (g.optional) " (optional)" else "", style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary)
                    Text(g.forStep, style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted)
                }
            }
            if (gear.isEmpty()) Text("Nothing special: just a bit of floor space.", style = MaterialTheme.typography.bodyLarge, color = AppColors.textPrimary)
        }
        BigActionButton("Ready", onReady, Modifier.fillMaxWidth().heightIn(min = 96.dp))
        SecondaryButton("Back", onBack, Modifier.fillMaxWidth(), accent = AppColors.textMuted)
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
                if (minutes >= 1) "$minutes min of ${if (ui.hipOnly) "hip prehab" else "knee care"} done and saved." else "Under a minute, so nothing will be logged.",
                style = MaterialTheme.typography.titleMedium, color = AppColors.textMuted,
            )
            if (minutes >= 1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    StatTile("Time", "$minutes", Modifier.weight(1f), unit = "min", compact = true)
                    if (ui.hipOnly) StatTile("Moves", "${ui.session?.names?.size ?: 0}", Modifier.weight(1f), accent = AppColors.reward, compact = true)
                    else {
                        StatTile("Calf sets", "${calfSets.size}", Modifier.weight(1f), accent = AppColors.reward, compact = true)
                        val top = calfSets.maxOfOrNull { it.weightKg }
                        StatTile("Top load", if (top == null || top <= 0.0) "BW" else "${if (top % 1.0 == 0.0) top.toInt() else top}", Modifier.weight(1f), unit = if (top != null && top > 0.0) "kg" else "", accent = AppColors.energy, compact = true)
                    }
                }
                if (calfSets.isNotEmpty()) {
                    Text(
                        "Calf raises: " + calfSets.joinToString(", ") { it.reps.toString() } + " reps. Your next target is worked out from this.",
                        style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
                    )
                }
                if (calfSets.isNotEmpty()) {
                    val kit = ui.session?.kit ?: KneeCareKit()
                    CalfLadder.startedAt(calfSets)?.let { lv ->
                        val reached = calfSets.maxOf { it.level ?: lv }
                        Text(CalfLadder.spec(reached, kit).levelLine, style = MaterialTheme.typography.titleMedium, color = AppColors.reward)
                    }
                    CalfLadder.nextLevel(calfSets)?.takeIf { CalfLadder.movedUp(calfSets) }?.let { next ->
                        Text("You moved up a level. Next time starts at ${CalfLadder.spec(next, kit).levelLine}.", style = MaterialTheme.typography.titleSmall, color = AppColors.primary)
                    }
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
