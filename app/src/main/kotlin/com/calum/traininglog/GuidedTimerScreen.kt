package com.calum.traininglog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.calum.traininglog.core.CueType
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.GuidedEvent
import com.calum.traininglog.core.GuidedKind
import com.calum.traininglog.core.GuidedPlan
import com.calum.traininglog.core.GuidedRun
import com.calum.traininglog.core.GuidedStep
import com.calum.traininglog.core.Modality
import com.calum.traininglog.core.PlannedSession
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.AppShapes
import com.calum.traininglog.ui.AppType
import com.calum.traininglog.ui.BigActionButton
import com.calum.traininglog.ui.BigNumberStepper
import com.calum.traininglog.ui.ConfettiBurst
import com.calum.traininglog.ui.Pill
import com.calum.traininglog.ui.SecondaryButton
import com.calum.traininglog.ui.SectionCard
import com.calum.traininglog.ui.SelectChip
import com.calum.traininglog.ui.Spacing
import com.calum.traininglog.ui.StatTile
import kotlinx.coroutines.delay

/*
 * Guided timer for a planned race-plan session on the rower, SkiErg or bike: setup (modality chips, effort in
 * plain words, the steps), a player (ring, next step, session clock, Pause / Skip / End) and a one-card rating.
 *
 * The clock is the monotonic SystemClock, fed to [GuidedTimerController.tick] with capped deltas. The session is
 * saved by the ViewModel the moment its last step ends; the rating card only edits that record.
 */

private fun mmss(sec: Int) = "${sec / 60}:${"%02d".format(sec % 60)}"

/** Short chip text for a modality. */
private fun chipLabel(m: Modality) = when (m) {
    Modality.ROW -> "Rower"
    Modality.SKIERG -> "SkiErg"
    Modality.BIKE -> "Bike"
    Modality.TREADMILL_WALK -> "Incline walk"
    Modality.TREADMILL_RUN -> "Treadmill run"
    Modality.RUN_WALK -> "Run-walk"
    Modality.HYROX_STATIONS -> "Hyrox stations"
    else -> m.label
}

@Composable
fun GuidedTimerScreen(vm: AppViewModel, session: PlannedSession) {
    val ui by vm.guided.ui.collectAsState()
    // Opened without the ViewModel having prepared it (e.g. restored): prepare the setup for this session.
    LaunchedEffect(session) {
        val u = vm.guided.ui.value
        if (u.phase == GuidedPhase.SETUP && u.session != session) vm.guided.prepare(session)
    }
    when (ui.phase) {
        GuidedPhase.SETUP -> {
            val plan = ui.plan ?: return
            GuidedSetupContent(
                session, ui.modality, plan,
                onModality = { m -> if (m in GuidedPlan.TIMER_MODALITIES) vm.guided.setModality(m) else vm.launchPlanned(session, m) },
                onStart = { vm.guided.start() },
                onBack = { vm.guidedBack() },
            )
        }
        GuidedPhase.RUNNING -> GuidedPlayer(vm, ui)
        GuidedPhase.DONE -> GuidedDoneContent(
            ui,
            onDistance = { vm.guided.adjustDistance(it) },
            onRpe = { vm.guided.setRpe(it) },
            onFeeling = { vm.guided.setFeeling(it) },
            onSave = { vm.saveGuided() },
        )
    }
}

// ---------------- Setup ----------------

@Composable
internal fun GuidedSetupContent(
    session: PlannedSession,
    modality: Modality,
    plan: GuidedPlan,
    onModality: (Modality) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    val chips = GuidedPlan.chipOptions(session)
    Column(
        Modifier.fillMaxSize().background(AppColors.background).verticalScroll(rememberScrollState())
            .statusBarsPadding().padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        if (chips.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                chips.forEach { m -> SelectChip(chipLabel(m), m == modality, { onModality(m) }) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(session.title, style = MaterialTheme.typography.headlineLarge, color = AppColors.textPrimary)
            Text("${session.minutes} minutes, ${modality.label.lowercase()}", style = MaterialTheme.typography.titleMedium, color = AppColors.textMuted)
        }
        EffortCard(plan, session)
        BigActionButton("Start", onStart, Modifier.heightIn(min = 96.dp))
        SectionCard(title = "Your steps") {
            summaryLines(plan).forEach { (lead, text) -> StepLine(lead, text) }
            Text(
                "A short beep 3 seconds before each step ends, a double beep at the switch, three rising beeps at the end.",
                style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
            )
        }
        if (session.detail.isNotEmpty()) {
            SectionCard(title = "From your plan") {
                session.detail.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.textPrimary) }
            }
        }
        if (session.why.isNotBlank()) {
            SectionCard(title = "Why this session") {
                Text(session.why, style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted)
            }
        }
        SecondaryButton("Back", onBack, Modifier.fillMaxWidth(), accent = AppColors.textMuted)
        Spacer(Modifier.height(Spacing.lg))
    }
}

@Composable
private fun EffortCard(plan: GuidedPlan, session: PlannedSession) {
    val hard = !plan.rpeTarget.let { it.last <= 4 }
    val accent = if (hard) AppColors.energy else AppColors.primary
    Column(
        Modifier.fillMaxWidth().clip(AppShapes.large).background(accent.copy(alpha = 0.12f))
            .border(1.5.dp, accent.copy(alpha = 0.5f), AppShapes.large).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text("HOW IT SHOULD FEEL", style = AppType.eyebrow, color = accent)
        Text(GuidedPlan.rpeWords(plan.rpeTarget), style = MaterialTheme.typography.headlineSmall, color = AppColors.textPrimary)
        if (plan.intervals) {
            Text("Between the hard parts: easy, full sentences again.", style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted)
        }
        if (session.includesRunning) {
            Text("Knee 2/10 or less. If it climbs, switch to walking.", style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted)
        }
    }
}

/** The steps in a few lines: intervals are grouped ("4 x 4 min hard, 3 min easy between"). */
internal fun summaryLines(plan: GuidedPlan): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    val steps = plan.steps
    val work = steps.filter { it.hard }
    steps.firstOrNull { it.kind == GuidedKind.WARMUP }?.let { out += mmss(it.seconds) to "Warm-up, easy" }
    if (work.isNotEmpty()) {
        val rest = steps.firstOrNull { it.kind == GuidedKind.RECOVERY }
        out += "${work.size} x ${work.first().seconds / 60}" to "min hard (RPE ${plan.rpeTarget.first}-${plan.rpeTarget.last})"
        if (rest != null) out += "${rest.seconds / 60} min" to "easy between each"
        steps.firstOrNull { it.kind == GuidedKind.EASY }?.let { out += mmss(it.seconds) to "Easy finish" }
    } else {
        val easy = steps.filter { it.kind == GuidedKind.EASY }
        out += mmss(easy.sumOf { it.seconds }) to "Easy" + if (easy.size > 1) ", with a check-in at halfway" else ""
    }
    steps.lastOrNull { it.kind == GuidedKind.COOLDOWN }?.let { out += mmss(it.seconds) to "Cool-down" }
    return out
}

@Composable
private fun StepLine(lead: String, text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(lead, style = AppType.numberS, color = AppColors.primary, modifier = Modifier.padding(end = Spacing.md).width(76.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = AppColors.textPrimary, modifier = Modifier.weight(1f))
    }
}

// ---------------- Player ----------------

@Composable
private fun GuidedPlayer(vm: AppViewModel, ui: GuidedUi) {
    val run = ui.run ?: return
    KeepScreenOn()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cues = remember { CuePlayer(ctx.applicationContext, scope) }
    DisposableEffect(Unit) { onDispose { cues.release() } }

    fun play(e: GuidedEvent?) = when (e) {
        GuidedEvent.PRE_WARNING -> cues.play(CueType.PRE_WARNING)
        GuidedEvent.CHANGE -> cues.play(CueType.CHANGE)
        GuidedEvent.DONE -> cues.play(CueType.DONE)
        null -> Unit
    }

    // Leaving the app (home, app switch, screen off) pauses the session.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev -> if (ev == Lifecycle.Event.ON_STOP) vm.guided.pauseAway() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // Monotonic clock tick. Deltas are capped so a stall can never fast-forward the session.
    LaunchedEffect(Unit) {
        var last = android.os.SystemClock.elapsedRealtime()
        while (true) {
            delay(100)
            val now = android.os.SystemClock.elapsedRealtime()
            val d = (now - last).coerceIn(0, 1000)
            last = now
            play(vm.guided.tick(d))
        }
    }

    var flash by remember { mutableStateOf(false) }
    var seen by remember { mutableIntStateOf(ui.changes) }
    LaunchedEffect(ui.changes) {
        if (ui.changes != seen) { seen = ui.changes; flash = true; delay(500); flash = false }
    }

    GuidedPlayerBody(
        run, ui.modality, flash,
        onTogglePause = { vm.guided.togglePause() },
        onSkip = { play(vm.guided.skip()) },
        onEnd = { vm.guided.end() },
    )
}

@Composable
private fun accentFor(step: GuidedStep, warn: Boolean): Color = when {
    warn -> AppColors.energy
    step.kind == GuidedKind.WORK -> AppColors.energy
    step.kind == GuidedKind.RECOVERY -> AppColors.info
    step.kind == GuidedKind.EASY -> AppColors.primary
    else -> AppColors.reward
}

@Composable
internal fun GuidedPlayerBody(run: GuidedRun, modality: Modality, flash: Boolean, onTogglePause: () -> Unit, onSkip: () -> Unit, onEnd: () -> Unit) {
    val step = run.current ?: return
    val dark = Color(0xFF05100B)

    if (flash) {
        Column(
            Modifier.fillMaxSize().background(AppColors.primary).padding(Spacing.screen),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Text(step.kind.label, color = dark, style = AppType.eyebrow)
            Text(step.name, color = dark, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
            Text(mmss(step.seconds), color = dark, style = AppType.numberXL)
        }
        return
    }

    val warn = !run.paused && run.secondsLeft in 1..3
    val accent = accentFor(step, warn)
    val stepFraction = (run.elapsedMs / (step.seconds * 1000f)).coerceIn(0f, 1f)
    val stepNo = run.index + 1

    Column(
        Modifier.fillMaxSize().background(AppColors.background).statusBarsPadding().padding(Spacing.screen),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            if (run.paused) Pill("PAUSED", color = AppColors.energy, filled = true) else Pill(step.kind.label, color = accent)
            Pill(chipLabel(modality), color = AppColors.textMuted)
        }
        Text(step.name, style = MaterialTheme.typography.displaySmall, color = AppColors.textPrimary, textAlign = TextAlign.Center)
        Text("Step $stepNo of ${run.steps.size}", style = MaterialTheme.typography.labelLarge, color = AppColors.textMuted)

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                CountdownRing(stepFraction, accent, Modifier.size(248.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(mmss(run.secondsLeft), style = AppType.numberXL, color = if (warn) AppColors.energy else AppColors.textPrimary)
                        Text("of ${mmss(step.seconds)}", style = MaterialTheme.typography.labelLarge, color = AppColors.textMuted)
                    }
                }
                if (run.pausedAway) {
                    Text(
                        "Paused while you were away. The session only runs while the app is open.",
                        style = MaterialTheme.typography.bodyMedium, color = AppColors.energy, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clip(AppShapes.medium).background(AppColors.energy.copy(alpha = 0.12f)).padding(Spacing.md),
                    )
                }
                if (step.checkIn) {
                    Text(
                        step.cue, style = MaterialTheme.typography.titleLarge, color = AppColors.textPrimary, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clip(AppShapes.medium).background(AppColors.primary.copy(alpha = 0.14f))
                            .border(1.5.dp, AppColors.primary.copy(alpha = 0.5f), AppShapes.medium).padding(Spacing.lg),
                    )
                } else {
                    Text(step.cue, style = MaterialTheme.typography.bodyLarge, color = AppColors.textPrimary, textAlign = TextAlign.Center)
                }
            }
        }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            LinearBar(run.fraction, Modifier.fillMaxWidth().height(16.dp))
            Text(
                "${run.completedSec / 60} of ${run.totalSec / 60} min", style = MaterialTheme.typography.labelLarge,
                color = AppColors.textMuted, modifier = Modifier.align(Alignment.End),
            )
        }
        run.next?.let { NextUpCard("${it.name}, ${mmss(it.seconds)}") }

        BigActionButton(if (run.paused) "Resume" else "Pause", onTogglePause)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            SecondaryButton("Skip step", onSkip, Modifier.weight(1f).heightIn(min = 64.dp))
            SecondaryButton("End session", onEnd, Modifier.weight(1f).heightIn(min = 64.dp), accent = AppColors.textMuted)
        }
    }
}

@Composable
private fun CountdownRing(fraction: Float, color: Color, modifier: Modifier, content: @Composable () -> Unit) {
    val track = AppColors.track
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 18.dp.toPx()
            val inset = sw / 2
            val arcSize = Size(size.width - sw, size.height - sw)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(sw))
            // Ring empties as the step runs down.
            drawArc(color, -90f, 360f * (1f - fraction), false, Offset(inset, inset), arcSize, style = Stroke(sw, cap = StrokeCap.Round))
        }
        content()
    }
}

@Composable
private fun LinearBar(progress: Float, modifier: Modifier) {
    Box(modifier.clip(AppShapes.chip).background(AppColors.track)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxSize().clip(AppShapes.chip).background(AppColors.gradientPrimary))
    }
}

@Composable
private fun NextUpCard(next: String) {
    Column(
        Modifier.fillMaxWidth().clip(AppShapes.medium).background(AppColors.info.copy(alpha = 0.10f))
            .border(1.dp, AppColors.info.copy(alpha = 0.4f), AppShapes.medium).padding(Spacing.md),
    ) {
        Text("NEXT UP", style = AppType.eyebrow, color = AppColors.info)
        Text(next, style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary)
    }
}

// ---------------- Finish / rating ----------------

private fun rpeHint(n: Int) = when (n) {
    1, 2 -> "Very easy"
    3, 4 -> "Easy: full sentences"
    5, 6 -> "Steady: a few words at a time"
    7 -> "Hard: short sentences"
    8 -> "Very hard"
    9 -> "Nearly everything"
    else -> "All out"
}

@Composable
internal fun GuidedDoneContent(
    ui: GuidedUi,
    onDistance: (Int) -> Unit,
    onRpe: (Int) -> Unit,
    onFeeling: (Feeling) -> Unit,
    onSave: () -> Unit,
) {
    var burst by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { burst = 1 }
    val run = ui.run
    val minutes = ((run?.completedSec ?: 0) + 30) / 60
    val enough = (run?.completedSec ?: 0) >= 60
    val intervals = ui.plan?.intervals == true
    BoxWithConstraints(Modifier.fillMaxSize().background(AppColors.background)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Text("Nice work", style = MaterialTheme.typography.displaySmall, color = AppColors.textPrimary)
            Text(
                if (enough) "$minutes min done. It is already saved; add the numbers if you like." else "Under a minute, so nothing will be logged.",
                style = MaterialTheme.typography.titleMedium, color = AppColors.textMuted,
            )
            if (enough) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    StatTile("Time", "$minutes", Modifier.weight(1f), unit = "min", compact = true)
                    if (intervals) StatTile("Hard", "${(run?.workSec ?: 0) / 60}", Modifier.weight(1f), unit = "min", accent = AppColors.energy, compact = true)
                }
                SectionCard(title = "Distance (optional)") {
                    BigNumberStepper(
                        value = ui.distanceM?.toString() ?: "-", unit = "m",
                        onMinus = { onDistance(-100) }, onPlus = { onDistance(100) },
                    )
                    SecondaryButton("+ 500 m", { onDistance(500) }, Modifier.fillMaxWidth())
                }
                SectionCard(title = if (intervals) "How hard were the hard parts?" else "How hard was it?") {
                    (listOf(1..5, 6..10)).forEach { rowRange ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            rowRange.forEach { n -> SelectChip("$n", ui.rpe == n, { onRpe(n) }, Modifier.weight(1f)) }
                        }
                    }
                    Text("${ui.rpe}/10: ${rpeHint(ui.rpe)}", style = MaterialTheme.typography.titleSmall, color = AppColors.textMuted)
                }
                SectionCard(title = "Feeling") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        listOf(Feeling.GOOD to "Good", Feeling.OKAY to "Okay", Feeling.ROUGH to "Rough").forEach { (f, label) ->
                            SelectChip(label, ui.feeling == f, { onFeeling(f) }, Modifier.weight(1f))
                        }
                    }
                }
            }
            BigActionButton(if (enough) "Save" else "Done", onSave)
            Spacer(Modifier.height(Spacing.lg))
        }
        ConfettiBurst(burst, Modifier.fillMaxSize(), particleCount = 120)
    }
}
