package com.calum.traininglog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.calum.traininglog.core.CoreEvent
import com.calum.traininglog.core.CueType
import com.calum.traininglog.core.CoreKind
import com.calum.traininglog.core.CoreRoutine
import com.calum.traininglog.core.CoreRun
import com.calum.traininglog.core.CoreSession
import com.calum.traininglog.core.CoreSide
import com.calum.traininglog.core.Effort
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.AppShapes
import com.calum.traininglog.ui.AppType
import com.calum.traininglog.ui.BigActionButton
import com.calum.traininglog.ui.ConfettiBurst
import com.calum.traininglog.ui.Pill
import com.calum.traininglog.ui.SecondaryButton
import com.calum.traininglog.ui.SectionCard
import com.calum.traininglog.ui.SelectChip
import com.calum.traininglog.ui.Spacing
import com.calum.traininglog.ui.TrainingTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/*
 * Guided mat / core session.
 *
 * Runs entirely in-process: a coroutine ticks a monotonic clock and advances [CoreRun]. It does NOT use the
 * foreground service, so if you leave the app the session pauses itself (screen off, app switch) and says
 * "Paused while you were away" when you come back; tap Resume to carry on where you were.
 */

enum class CorePhase { SETUP, RUNNING, DONE }

data class CoreUi(
    val minutes: Int = 20,
    val kneeSensitive: Boolean = false,
    val seed: Long = 0,
    val session: CoreSession? = null,
    val phase: CorePhase = CorePhase.SETUP,
    val run: CoreRun? = null,
    /** Beep-and-flash counter: bumped on each step change so the screen can flash. */
    val changes: Int = 0,
)

/** State holder for the guided core session; lives in [AppViewModel] so rotation does not lose it. */
class CoreController {
    private val _ui = MutableStateFlow(CoreUi())
    val ui: StateFlow<CoreUi> = _ui.asStateFlow()

    fun prepare(minutes: Int, last: List<String>, kneeSensitive: Boolean) {
        val m = (minutes.coerceIn(CoreRoutine.MIN_MINUTES, CoreRoutine.MAX_MINUTES) + 2) / 5 * 5
        val seed = System.currentTimeMillis() / 1000
        _ui.value = CoreUi(minutes = m, kneeSensitive = kneeSensitive, seed = seed, session = CoreRoutine.build(m, last = last, seed = seed, kneeSensitive = kneeSensitive))
    }

    fun rebuild(last: List<String>, minutes: Int? = null, kneeSensitive: Boolean? = null, reshuffle: Boolean = false) = _ui.update {
        val m = minutes ?: it.minutes
        val k = kneeSensitive ?: it.kneeSensitive
        val seed = if (reshuffle) it.seed + 1 else it.seed
        it.copy(minutes = m, kneeSensitive = k, seed = seed, session = CoreRoutine.build(m, last = last, seed = seed, kneeSensitive = k))
    }

    fun start() = _ui.update { u ->
        val s = u.session ?: return@update u
        u.copy(phase = CorePhase.RUNNING, run = CoreRun(s.steps), changes = 0)
    }

    /** Advances the clock; returns the cue to play, if any. */
    fun tick(deltaMs: Long): CoreEvent? {
        var event: CoreEvent? = null
        _ui.update { u ->
            val run = u.run ?: return@update u
            if (u.phase != CorePhase.RUNNING) return@update u
            val (next, e) = run.advance(deltaMs)
            event = e
            u.copy(run = next, phase = if (next.finished) CorePhase.DONE else u.phase, changes = u.changes + if (e == CoreEvent.CHANGE) 1 else 0)
        }
        return event
    }

    fun togglePause() = _ui.update { u -> u.run?.let { r -> u.copy(run = if (r.paused) r.resume() else r.pause()) } ?: u }

    /** The app left the foreground: pause and remember why. */
    fun pauseAway() = _ui.update { u ->
        val r = u.run
        if (u.phase == CorePhase.RUNNING && r != null && !r.paused) u.copy(run = r.pause(away = true)) else u
    }

    fun skip(): CoreEvent? {
        var event: CoreEvent? = null
        _ui.update { u ->
            val run = u.run ?: return@update u
            val (next, e) = run.skip()
            event = e
            u.copy(run = next, phase = if (next.finished) CorePhase.DONE else u.phase, changes = u.changes + 1)
        }
        return event
    }

    fun end() = _ui.update { it.copy(phase = CorePhase.DONE) }

    fun reset() { _ui.value = CoreUi() }
}

// ---------------- Screen ----------------

@Composable
fun CoreSessionScreen(vm: AppViewModel) {
    val ui by vm.core.ui.collectAsState()
    when (ui.phase) {
        CorePhase.SETUP -> CoreSetupContent(
            ui,
            onMinutes = { vm.coreSetup(minutes = it) },
            onKnee = { vm.coreSetup(kneeSensitive = it) },
            onShuffle = { vm.coreSetup(reshuffle = true) },
            onStart = { vm.core.start() },
            onBack = { vm.go(Screen.Start) },
        )
        CorePhase.RUNNING -> CorePlayer(vm, ui)
        CorePhase.DONE -> CoreDoneScreen(ui, onSave = { vm.saveCore(it) })
    }
}

@Composable
private fun CorePlayer(vm: AppViewModel, ui: CoreUi) {
    val run = ui.run ?: return
    KeepScreenOn()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cues = remember { CuePlayer(ctx.applicationContext, scope) }
    DisposableEffect(Unit) { onDispose { cues.release() } }

    fun play(e: CoreEvent?) = when (e) {
        CoreEvent.PRE_WARNING -> cues.play(CueType.PRE_WARNING)
        CoreEvent.CHANGE -> cues.play(CueType.CHANGE)
        CoreEvent.DONE -> cues.play(CueType.DONE)
        null -> Unit
    }

    // Leaving the app (home, app switch, screen off) pauses the session.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev -> if (ev == Lifecycle.Event.ON_STOP) vm.core.pauseAway() }
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
            play(vm.core.tick(d))
        }
    }

    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(ui.changes) {
        if (ui.changes > 0) { flash = true; delay(500); flash = false }
    }

    CorePlayerBody(
        run, flash,
        onTogglePause = { vm.core.togglePause() },
        onSkip = { play(vm.core.skip()) },
        onEnd = { vm.core.end() },
    )
}

private fun mmss(sec: Int) = "${sec / 60}:${"%02d".format(sec % 60)}"

private fun CoreKind.title() = when (this) {
    CoreKind.WARMUP -> "WARM-UP"
    CoreKind.WORK -> "WORK"
    CoreKind.REST -> "REST"
    CoreKind.COOLDOWN -> "COOL-DOWN"
}

private fun sideText(s: CoreSide?) = when (s) { CoreSide.LEFT -> "LEFT SIDE"; CoreSide.RIGHT -> "RIGHT SIDE"; null -> null }

@Composable
private fun CorePlayerBody(run: CoreRun, flash: Boolean, onTogglePause: () -> Unit, onSkip: () -> Unit, onEnd: () -> Unit) {
    val step = run.current ?: return
    val dark = Color(0xFF05100B)

    if (flash) {
        Column(
            Modifier.fillMaxSize().background(AppColors.primary).padding(Spacing.screen),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Text(step.kind.title(), color = dark, style = AppType.eyebrow)
            Text(step.name, color = dark, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
            sideText(step.side)?.let { Text(it, color = dark, style = MaterialTheme.typography.headlineMedium) }
            Text("${step.seconds}s", color = dark, style = AppType.numberXL)
        }
        return
    }

    val warn = step.kind == CoreKind.WORK && !run.paused && run.secondsLeft in 1..3
    val accent = when {
        warn -> AppColors.energy
        step.kind == CoreKind.REST -> AppColors.info
        step.kind == CoreKind.WORK -> AppColors.primary
        else -> AppColors.reward
    }
    val stepFraction = (run.elapsedMs / (step.seconds * 1000f)).coerceIn(0f, 1f)

    Column(
        Modifier.fillMaxSize().background(AppColors.background).verticalScroll(rememberScrollState())
            .statusBarsPadding().padding(Spacing.screen),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            if (run.paused) "PAUSED" else step.kind.title(),
            style = AppType.eyebrow, color = if (run.paused) AppColors.energy else AppColors.textMuted,
        )
        Text(step.name, style = MaterialTheme.typography.displaySmall, color = AppColors.textPrimary, textAlign = TextAlign.Center)
        sideText(step.side)?.let { Pill(it, color = AppColors.info, filled = true) }

        CountdownRing(stepFraction, accent, Modifier.size(220.dp)) {
            Text(mmss(run.secondsLeft), style = AppType.numberXL, color = if (warn) AppColors.energy else AppColors.textPrimary)
        }

        if (run.pausedAway) {
            Text(
                "Paused while you were away. The session only runs while the app is open.",
                style = MaterialTheme.typography.bodyMedium, color = AppColors.energy, textAlign = TextAlign.Center,
            )
        }

        Text(step.cue, style = MaterialTheme.typography.bodyLarge, color = AppColors.textPrimary, textAlign = TextAlign.Center)
        step.reps?.let { Text("Aim for about $it slow reps", style = MaterialTheme.typography.labelLarge, color = AppColors.textMuted) }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            LinearBar(run.fraction, Modifier.fillMaxWidth().height(16.dp))
            Text(
                "${run.completedSec / 60} of ${run.totalSec / 60} min", style = MaterialTheme.typography.labelLarge,
                color = AppColors.textMuted, modifier = Modifier.align(Alignment.End),
            )
        }
        step.nextName?.let { NextUpCard(it) }

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
            .border(1.dp, AppColors.info.copy(alpha = 0.4f), AppShapes.medium).padding(Spacing.lg),
    ) {
        Text("NEXT UP", style = AppType.eyebrow, color = AppColors.info)
        Text(next, style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary)
    }
}

// ---------------- Setup ----------------

@Composable
private fun CoreSetupContent(
    ui: CoreUi,
    onMinutes: (Int) -> Unit,
    onKnee: (Boolean) -> Unit,
    onShuffle: () -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    val s = ui.session
    Column(
        Modifier.fillMaxSize().background(AppColors.background).verticalScroll(rememberScrollState())
            .statusBarsPadding().padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text("Core and mat", style = MaterialTheme.typography.headlineLarge, color = AppColors.textPrimary)
            Text("${ui.minutes} minutes, no equipment, no impact", style = MaterialTheme.typography.titleMedium, color = AppColors.textMuted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
            listOf(10, 15, 20, 25, 30).forEach { m -> SelectChip("$m", ui.minutes == m, { onMinutes(m) }, Modifier.weight(1f)) }
        }
        Row(
            Modifier.fillMaxWidth().clip(AppShapes.medium).background(AppColors.surfaceRaised).padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Knee sensitive", style = MaterialTheme.typography.titleSmall, color = AppColors.textPrimary)
                Text("Leaves out moves that load the knee", style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
            }
            Switch(
                ui.kneeSensitive, onKnee,
                colors = SwitchDefaults.colors(checkedTrackColor = AppColors.primary, checkedThumbColor = AppColors.onPrimary),
            )
        }
        if (s != null) {
            SectionCard(title = "Warm-up") {
                s.warmup.forEach { StepLine("${it.seconds}s", it.name, null) }
            }
            s.blocks.forEachIndexed { i, b ->
                SectionCard(title = "Block ${i + 1}: ${b.rounds} ${if (b.rounds == 1) "round" else "rounds"}, ${b.workSec}s on / ${b.restSec}s off") {
                    b.exercises.forEach { e ->
                        StepLine(
                            if (e.reps != null) "${e.reps}" else "hold", e.name,
                            if (e.unilateral) "each side" else null,
                        )
                    }
                }
            }
            SectionCard(title = "Cool-down") {
                s.cooldown.filter { it.side == CoreSide.LEFT }.forEach { StepLine("${it.seconds}s", it.name, "each side") }
            }
        }
        Text(
            "You get a short beep 3 seconds before each work interval ends and a double beep at the switch. " +
                "The timer runs inside the app: if you leave it, the session pauses and waits for you.",
            style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
        )
        BigActionButton("Start", onStart, Modifier.heightIn(min = 96.dp), enabled = s != null)
        SecondaryButton("Different mix", onShuffle, Modifier.fillMaxWidth())
        SecondaryButton("Back", onBack, Modifier.fillMaxWidth(), accent = AppColors.textMuted)
    }
}

@Composable
private fun StepLine(lead: String, name: String, tail: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(lead, style = AppType.numberS, color = AppColors.primary, modifier = Modifier.padding(end = Spacing.md).width(56.dp))
        Text(name, style = MaterialTheme.typography.titleSmall, color = AppColors.textPrimary, modifier = Modifier.weight(1f))
        if (tail != null) Text(tail, style = MaterialTheme.typography.labelMedium, color = AppColors.textMuted)
    }
}

// ---------------- Done ----------------

@Composable
private fun CoreDoneScreen(ui: CoreUi, onSave: (Effort?) -> Unit) {
    var burst by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { burst = 1 }
    val minutes = (ui.run?.completedSec ?: 0) / 60
    Box(Modifier.fillMaxSize().background(AppColors.background)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Text("Nice work", style = MaterialTheme.typography.displaySmall, color = AppColors.textPrimary)
            Text(
                if (minutes >= 1) "$minutes min of core done." else "Under a minute, so nothing will be logged.",
                style = MaterialTheme.typography.titleMedium, color = AppColors.textMuted,
            )
            if (minutes >= 1) {
                Text("How did that feel? (optional)", style = MaterialTheme.typography.headlineSmall, color = AppColors.textPrimary)
                DoneTile("Too easy", "Next time push harder", AppColors.energy) { onSave(Effort.TOO_EASY) }
                DoneTile("Just right", "Keep it like this", AppColors.primary) { onSave(Effort.JUST_RIGHT) }
                DoneTile("Too hard", "Take it a bit easier", AppColors.info) { onSave(Effort.TOO_HARD) }
            }
            SecondaryButton(if (minutes >= 1) "Skip" else "Done", { onSave(null) }, Modifier.fillMaxWidth(), accent = AppColors.textMuted)
        }
        ConfettiBurst(burst, Modifier.fillMaxSize(), particleCount = 120)
    }
}

@Composable
private fun DoneTile(title: String, subtitle: String, accent: Color, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Column(
        Modifier.fillMaxWidth().heightIn(min = 88.dp)
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

private val previewSession = CoreRoutine.build(20, seed = 5, kneeSensitive = true)

@Preview(widthDp = 380, heightDp = 1400)
@Composable
private fun CoreSetupPreview() = TrainingTheme {
    CoreSetupContent(CoreUi(20, true, 5, previewSession), {}, {}, {}, {}, {})
}

@Preview(widthDp = 380, heightDp = 900)
@Composable
private fun CorePlayerPreview() = TrainingTheme {
    val run = CoreRun(previewSession.steps, index = 6, elapsedMs = 17_000)
    CorePlayerBody(run, false, {}, {}, {})
}

@Preview(widthDp = 380, heightDp = 900)
@Composable
private fun CorePlayerAwayPreview() = TrainingTheme {
    val run = CoreRun(previewSession.steps, index = 4, elapsedMs = 9_000).pause(away = true)
    CorePlayerBody(run, false, {}, {}, {})
}

@Preview(widthDp = 380, heightDp = 700)
@Composable
private fun CoreDonePreview() = TrainingTheme {
    CoreDoneScreen(CoreUi(run = CoreRun(previewSession.steps, index = previewSession.steps.size, totalElapsedMs = 1_200_000), phase = CorePhase.DONE), {})
}
