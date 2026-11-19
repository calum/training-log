package com.calum.traininglog

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calum.traininglog.core.CueType
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.HyroxGuide
import com.calum.traininglog.core.HyroxPlace
import com.calum.traininglog.core.HyroxRuns
import com.calum.traininglog.core.HyroxSessionPlan
import com.calum.traininglog.core.HyroxStation as Station
import com.calum.traininglog.core.StationBlock
import com.calum.traininglog.core.StationKind
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.AppShapes
import com.calum.traininglog.ui.AppType
import com.calum.traininglog.ui.BigActionButton
import com.calum.traininglog.ui.BigNumberStepper
import com.calum.traininglog.ui.ConfettiBurst
import com.calum.traininglog.ui.Pill
import com.calum.traininglog.ui.RestTimerBar
import com.calum.traininglog.ui.SecondaryButton
import com.calum.traininglog.ui.SectionCard
import com.calum.traininglog.ui.SelectChip
import com.calum.traininglog.ui.Spacing
import com.calum.traininglog.ui.StatTile
import kotlinx.coroutines.delay

/**
 * Guided Hyrox station session: setup, player and finish behind one route. The state lives in [HyroxController]
 * (inside the ViewModel), so rotating or leaving the screen for a moment loses nothing; every clock is monotonic.
 */
@Composable
fun HyroxSessionScreen(vm: AppViewModel, focus: Set<Station>) {
    val ui by vm.hyrox.ui.collectAsState()
    val data by vm.data.collectAsState()
    // Opened from a hub or station page with a focus: prepare the setup for it (a running session is left alone).
    LaunchedEffect(focus) {
        val u = vm.hyrox.ui.value
        if (u.phase == HyroxPhase.SETUP) {
            // Re-plan on every visit: the kit or the place may have been changed since the setup was last open.
            if (u.plan == null || u.entryFocus != focus) vm.hyroxPrepare(focus) else vm.hyroxSetup()
        }
    }
    when (ui.phase) {
        HyroxPhase.SETUP -> HyroxSetupContent(
            ui, lastKnee = data.records.filter { it.kneePain != null }.sortedWith(compareBy({ it.date }, { it.id })).lastOrNull()?.kneePain,
            onMinutes = { vm.hyroxSetup(minutes = it) },
            onFocus = { st -> vm.hyroxSetup(focus = if (st in ui.focus) ui.focus - st else ui.focus + st) },
            onRuns = { vm.hyroxSetup(withRuns = it) },
            onPlace = { vm.hyroxSetPlace(it) },
            onRunDistance = { vm.hyroxSetup(runMetres = it) },
            runSpeedKmh = data.profile.settings.hyroxRunSpeedKmh,
            runInclinePct = data.profile.settings.hyroxRunInclinePct,
            onShuffle = { vm.hyroxSetup(shuffle = true) },
            onStart = { vm.hyrox.start() },
            onBack = { vm.back() },
        )
        HyroxPhase.RUNNING -> HyroxPlayer(vm, ui)
        HyroxPhase.DONE -> HyroxFinish(vm, ui)
    }
}

private fun runNoteText(): String =
    "Running is optional. Keep it easy and conversational, and stop if your right knee flares."

// ---------------------------------------------------------------------------------------------
// Setup
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HyroxSetupContent(
    ui: HyroxUi,
    lastKnee: Int?,
    onMinutes: (Int) -> Unit,
    onFocus: (Station) -> Unit,
    onRuns: (Boolean) -> Unit,
    onShuffle: () -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
    onPlace: (HyroxPlace) -> Unit = {},
    onRunDistance: (Int) -> Unit = {},
    runSpeedKmh: Double = 9.0,
    runInclinePct: Double = 1.0,
) {
    val plan = ui.plan
    val kneeOk = lastKnee == null || lastKnee < HyroxPlanInput.KNEE_RUN_LIMIT
    Column(
        Modifier.fillMaxSize().background(AppColors.background).verticalScroll(rememberScrollState())
            .statusBarsPadding().padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text("Hyrox session", style = MaterialTheme.typography.headlineLarge, color = AppColors.textPrimary)
            Text(
                if (plan != null && plan.blocks.isNotEmpty()) "${plan.blocks.size} ${if (plan.blocks.size == 1) "station" else "stations"}, about ${plan.estimatedMinutes} minutes"
                else "Pick your time and stations",
                style = MaterialTheme.typography.titleMedium, color = AppColors.textMuted,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FieldLabel("WHERE ARE YOU TRAINING?")
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                HyroxPlace.entries.forEach { p -> SelectChip(p.label, ui.place == p, { onPlace(p) }, Modifier.weight(1f)) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FieldLabel("HOW LONG?")
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                HyroxController.MINUTE_CHOICES.forEach { m -> SelectChip("$m", ui.minutes == m, { onMinutes(m) }, Modifier.weight(1f)) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FieldLabel("FOCUS (OPTIONAL)")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Station.entries.forEach { st -> SelectChip(st.displayName, st in ui.focus, { onFocus(st) }) }
            }
            Text(
                if (ui.focus.isEmpty()) "Nothing picked: you get a recommended mix based on what you did last." else "Planning around your picks.",
                style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
            )
        }
        if (kneeOk) {
            val spec = plan?.runSpec
            val treadmillHere = spec?.treadmill ?: ui.treadmillHere
            Column(
                Modifier.fillMaxWidth().clip(AppShapes.medium).background(AppColors.surfaceRaised).padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Include runs between stations", style = MaterialTheme.typography.titleSmall, color = AppColors.textPrimary)
                        Text(runNoteText(), style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
                    }
                    Switch(
                        ui.withRuns, onRuns,
                        colors = SwitchDefaults.colors(checkedTrackColor = AppColors.primary, checkedThumbColor = AppColors.onPrimary),
                    )
                }
                if (ui.withRuns) {
                    FieldLabel("RUN DISTANCE")
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                        HyroxRuns.DISTANCE_CHOICES.forEach { m -> SelectChip("$m m", ui.runMetres == m, { onRunDistance(m) }, Modifier.weight(1f)) }
                    }
                    Text(
                        if (treadmillHere) {
                            "Treadmill runs at ${HyroxText.speed(runSpeedKmh)} km/h, ${HyroxText.incline(runInclinePct)}% incline: " +
                                "${HyroxText.clock(HyroxRuns.targetSec(ui.runMetres, runSpeedKmh))} each, counted down for you."
                        } else "No treadmill ticked here, so you time each run with a stopwatch.",
                        style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
                    )
                }
            }
        } else {
            Column(
                Modifier.fillMaxWidth().clip(AppShapes.medium).background(AppColors.surfaceRaised).padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text("Runs between stations", style = MaterialTheme.typography.titleSmall, color = AppColors.textPrimary)
                Text(
                    "Left out for now: your knee score was $lastKnee last time, so this session is strength stations only. " +
                        "Runs come back as an option once the knee is calmer.",
                    style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
                )
            }
        }

        if (plan != null && plan.unavailable.isNotEmpty()) {
            Text(
                "Not available here: " + plan.unavailable.joinToString(", ") { it.displayName } +
                    ". Tick your kit under My equipment on the Hyrox page.",
                style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
            )
        }
        FieldLabel("TODAY'S PLAN")
        if (plan == null || plan.blocks.isEmpty()) {
            SectionCard { Text("Nothing fits in that time. Try a longer session, or pick fewer stations.", color = AppColors.textMuted) }
        } else {
            plan.blocks.forEachIndexed { i, b -> PlanBlockCard(i + 1, b, plan) }
            SectionCard(title = "Warm-up") { Text(plan.warmupNote, style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted) }
            plan.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted) }
        }

        BigActionButton("Start", onStart, enabled = plan != null && plan.blocks.isNotEmpty())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            SecondaryButton("Different mix", onShuffle, Modifier.weight(1f))
            SecondaryButton("Back", onBack, Modifier.weight(1f), accent = AppColors.textMuted)
        }
        Text(
            "Tap Go when a round starts and Done when it ends. You get a beep when each rest is over. " +
                "The clocks keep counting if you glance at another app.",
            style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
        )
        Spacer(Modifier.height(Spacing.lg).navigationBarsPadding())
    }
}

@Composable
private fun PlanBlockCard(n: Int, b: StationBlock, plan: HyroxSessionPlan) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text("$n", style = AppType.numberS, color = AppColors.primary)
            Text(b.station.displayName, style = MaterialTheme.typography.titleLarge, color = AppColors.textPrimary, modifier = Modifier.weight(1f))
            Text("~${b.estimatedMin} min", style = MaterialTheme.typography.labelLarge, color = AppColors.textMuted)
        }
        Text(HyroxText.prescription(b.station, b.prescription), style = MaterialTheme.typography.bodyLarge, color = AppColors.textPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Pill("Level ${b.prescription.level}: ${b.prescription.label}", color = AppColors.reward)
            if (b.station.kneeHeavy) Pill("Knee-aware", color = AppColors.info)
        }
        if (b.runAfterM > 0) {
            val spec = plan.runSpec
            Text(
                if (spec != null && spec.treadmill) {
                    "then a ${b.runAfterM} m treadmill run at ${HyroxText.speed(spec.speedKmh)} km/h (${HyroxText.clock(HyroxRuns.targetSec(b.runAfterM, spec.speedKmh))}, optional)"
                } else "then an easy ${b.runAfterM} m run (optional)",
                style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Player
// ---------------------------------------------------------------------------------------------

@Composable
private fun HyroxPlayer(vm: AppViewModel, ui: HyroxUi) {
    KeepScreenOn()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cues = remember { CuePlayer(ctx.applicationContext, scope) }
    DisposableEffect(Unit) { onDispose { cues.release() } }

    // Monotonic clock: laps, rests and the session clock are all differences of this value.
    var nowMs by remember { mutableLongStateOf(vm.hyrox.nowMs()) }
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        var warned = -1L
        var warnedRun = -1L
        while (true) {
            nowMs = vm.hyrox.nowMs()
            val u = vm.hyrox.ui.value
            if (u.step == HyroxStep.REST) {
                val left = (u.restEndsAt - nowMs) / 1000
                if (left in 1..3 && warned != u.restEndsAt) { warned = u.restEndsAt; cues.play(CueType.PRE_WARNING) }
            }
            if (u.step == HyroxStep.RUN && u.runStartedAt != null && u.runTargetSec != null) {
                val key = u.runStartedAt + u.runTargetSec * 1000L
                if (key - nowMs in 1000..3000 && warnedRun != key) { warnedRun = key; cues.play(CueType.PRE_WARNING) }
            }
            if (vm.hyrox.tick()) cues.play(CueType.CHANGE)
            if (vm.hyrox.tickRun()) cues.play(CueType.DONE)
            delay(200)
        }
    }
    LaunchedEffect(ui.changes) {
        if (ui.changes > 0) { flash = true; delay(800); flash = false }
    }
    // A double beep when the last lap of a block ends.
    LaunchedEffect(ui.step, ui.index) { if (ui.step == HyroxStep.RATE) cues.play(CueType.DONE) }

    HyroxPlayerBody(
        ui, nowMs, flash,
        onGo = { vm.hyrox.go() }, onLapDone = { vm.hyrox.lapDone() }, onSkipRest = { vm.hyrox.skipRest() },
        onWeight = { vm.hyrox.adjustWeight(it) }, onDistance = { vm.hyrox.adjustDistance(it) }, onReps = { vm.hyrox.adjustReps(it) },
        onRpe = { vm.hyrox.setRpe(it) }, onTechnique = { vm.hyrox.setTechnique(it) }, onSaveBlock = { vm.hyrox.saveBlock() },
        onRunGo = { vm.hyrox.runGo() }, onRunDone = { vm.hyrox.runDone() }, onSkipRun = { vm.hyrox.skipRun() },
        onRunMore = { vm.hyrox.runAddTime() }, onRunRpe = { vm.hyrox.setRunRpe(it) },
        onSkipStation = { vm.hyrox.skipStation() }, onEnd = { vm.hyrox.endEarly() },
    )
}

@Composable
internal fun HyroxPlayerBody(
    ui: HyroxUi,
    nowMs: Long,
    flash: Boolean,
    onGo: () -> Unit, onLapDone: () -> Unit, onSkipRest: () -> Unit,
    onWeight: (Int) -> Unit, onDistance: (Int) -> Unit, onReps: (Int) -> Unit,
    onRpe: (Int) -> Unit, onTechnique: (Int) -> Unit, onSaveBlock: () -> Unit,
    onRunGo: () -> Unit, onRunDone: () -> Unit, onSkipRun: () -> Unit,
    onSkipStation: () -> Unit, onEnd: () -> Unit,
    onRunMore: () -> Unit = {}, onRunRpe: (Int) -> Unit = {},
) {
    val block = ui.block ?: return
    val st = block.station
    val flashBg by animateColorAsState(if (flash) AppColors.primary.copy(alpha = 0.22f) else AppColors.background, tween(200), label = "hxFlash")
    val elapsedSec = ((nowMs - ui.startedAt) / 1000).toInt().coerceAtLeast(0)
    val windowSec = (ui.minutes.coerceAtLeast(1)) * 60

    Column(Modifier.fillMaxSize().background(flashBg).statusBarsPadding()) {
        SessionClock(elapsedSec, windowSec)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (ui.step == HyroxStep.RUN) {
                RunBlock(ui, nowMs, block, onRunGo, onRunDone, onSkipRun, onRunMore, onRunRpe)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("STATION ${ui.index + 1} OF ${ui.blocks.size}", style = AppType.eyebrow, color = AppColors.textMuted, modifier = Modifier.weight(1f))
                    if (ui.totalRounds > 1 && ui.step != HyroxStep.RATE) {
                        Pill("Round ${(ui.round + 1).coerceAtMost(ui.totalRounds)} of ${ui.totalRounds}", color = AppColors.info)
                    }
                }
                Text(st.displayName, style = MaterialTheme.typography.displaySmall, color = AppColors.textPrimary)
                Text(HyroxText.prescription(st, block.prescription), style = MaterialTheme.typography.titleLarge, color = AppColors.primary)
                RememberCard(st, open = ui.step == HyroxStep.READY, key = ui.index to ui.step)

                when (ui.step) {
                    HyroxStep.READY, HyroxStep.LAP -> LapStopwatch(ui, nowMs, onGo, onLapDone)
                    HyroxStep.REST -> RestBlock(ui, nowMs, block, onSkipRest)
                    HyroxStep.RATE -> RateBlock(ui, block, onWeight, onDistance, onReps, onRpe, onTechnique, onSaveBlock)
                    HyroxStep.RUN -> Unit
                }
            }
            if (ui.step != HyroxStep.RATE) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    if (ui.step != HyroxStep.RUN) SecondaryButton("Skip this station", onSkipStation, Modifier.weight(1f).heightIn(min = 64.dp))
                    SecondaryButton("End session early", onEnd, Modifier.weight(1f).heightIn(min = 64.dp), accent = AppColors.textMuted)
                }
            } else {
                SecondaryButton("End session early", onEnd, Modifier.fillMaxWidth(), accent = AppColors.textMuted)
            }
            Spacer(Modifier.height(Spacing.lg).navigationBarsPadding())
        }
    }
}

/** Slim always-visible session clock, like the gym's. Going over the planned time just turns it amber. */
@Composable
private fun SessionClock(elapsedSec: Int, windowSec: Int) {
    val over = elapsedSec > windowSec
    val accent = if (over) AppColors.energy else AppColors.primary
    val fraction by animateFloatAsState((elapsedSec / windowSec.toFloat()).coerceIn(0f, 1f), tween(400), label = "hxClock")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(HyroxText.clock(elapsedSec), style = MaterialTheme.typography.labelLarge, color = AppColors.textPrimary, fontWeight = FontWeight.Bold)
        Box(Modifier.weight(1f).height(6.dp).clip(AppShapes.chip).background(AppColors.track)) {
            Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(AppShapes.chip).background(accent))
        }
        Text(
            if (over) "+${(elapsedSec - windowSec + 59) / 60} min" else "of ${windowSec / 60} min",
            style = MaterialTheme.typography.labelMedium, color = if (over) AppColors.energy else AppColors.textMuted,
        )
    }
}

/** Collapsible card with the two top technique tips (and the knee note on the knee-heavy stations). */
@Composable
private fun RememberCard(station: Station, open: Boolean, key: Any) {
    var expanded by remember(key) { mutableStateOf(open) }
    val guide = HyroxGuide.of(station)
    Column(
        Modifier.fillMaxWidth().clip(AppShapes.medium).background(AppColors.info.copy(alpha = 0.10f))
            .border(1.dp, AppColors.info.copy(alpha = 0.4f), AppShapes.medium)
            .clickable { expanded = !expanded }.padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("REMEMBER", style = AppType.eyebrow, color = AppColors.info, modifier = Modifier.weight(1f))
            Text(if (expanded) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, color = AppColors.info)
        }
        if (expanded) {
            guide.tips.take(2).forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge, color = AppColors.textPrimary) }
            if (station.kneeHeavy) guide.kneeNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted) }
        } else {
            Text(guide.summary, style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted, maxLines = 2)
        }
    }
}

@Composable
private fun LapStopwatch(ui: HyroxUi, nowMs: Long, onGo: () -> Unit, onDone: () -> Unit) {
    val running = ui.step == HyroxStep.LAP && ui.lapStartedAt != null
    val sec = if (running) ((nowMs - ui.lapStartedAt!!) / 1000).toInt().coerceAtLeast(0) else 0
    Column(
        Modifier.fillMaxWidth().clip(AppShapes.large).background(AppColors.surface)
            .border(1.dp, AppColors.outline.copy(alpha = 0.5f), AppShapes.large).padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(if (running) "LAP TIME" else "READY", style = AppType.eyebrow, color = AppColors.textMuted)
        Text(
            HyroxText.clock(sec), color = if (running) AppColors.primary else AppColors.textMuted,
            style = AppType.numberHero.copy(fontSize = 96.sp, lineHeight = 100.sp),
        )
        if (ui.laps.isNotEmpty()) {
            Text(
                ui.laps.mapIndexed { i, s -> "Round ${i + 1}: ${HyroxText.clock(s)}" }.joinToString("   "),
                style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted, textAlign = TextAlign.Center,
            )
        }
        if (running) BigActionButton("Done", onDone, Modifier.heightIn(min = 96.dp), brush = AppColors.gradientReward)
        else BigActionButton("Go", onGo, Modifier.heightIn(min = 96.dp))
    }
}

@Composable
private fun RestBlock(ui: HyroxUi, nowMs: Long, block: StationBlock, onSkip: () -> Unit) {
    val left = (((ui.restEndsAt - nowMs) / 1000).toInt() + 1).coerceIn(0, ui.restTotalSec.coerceAtLeast(1))
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text("Nice work, round ${ui.round} done", style = MaterialTheme.typography.headlineSmall, color = AppColors.textPrimary)
        RestTimerBar(remainingSec = left, totalSec = ui.restTotalSec, onSkip = onSkip)
        Text(
            "Next: round ${ui.round + 1} of ${ui.totalRounds}, ${HyroxText.prescription(block.station, block.prescription)}",
            style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
        )
        if (ui.laps.isNotEmpty()) {
            Text(
                ui.laps.mapIndexed { i, s -> "Round ${i + 1}: ${HyroxText.clock(s)}" }.joinToString("   "),
                style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
            )
        }
    }
}

@Composable
private fun RateBlock(
    ui: HyroxUi, block: StationBlock,
    onWeight: (Int) -> Unit, onDistance: (Int) -> Unit, onReps: (Int) -> Unit,
    onRpe: (Int) -> Unit, onTechnique: (Int) -> Unit, onSave: () -> Unit,
) {
    val st = block.station
    val a = ui.actual
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text("Nice work", style = MaterialTheme.typography.headlineSmall, color = AppColors.textPrimary)
        Text(
            "Time on the clock: ${HyroxText.clock(ui.laps.sum())}. Fix anything that was different from the plan.",
            style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
        )
        SectionCard {
            a.weightKg?.let { w ->
                BigNumberStepper(fmt(w), "kg", { onWeight(-1) }, { onWeight(1) }, label = if (st == Station.FARMERS_CARRY) "Weight per hand" else "Weight")
            }
            a.distanceM?.let { d -> BigNumberStepper("$d", "m", { onDistance(-1) }, { onDistance(1) }, label = "Total distance") }
            a.reps?.let { r -> BigNumberStepper("$r", "reps", { onReps(-1) }, { onReps(1) }, label = "Total reps") }
        }
        FieldLabel("EFFORT (6 EASY, 10 ALL OUT)")
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
            (6..10).forEach { v -> SelectChip("$v", ui.rpe == v, { onRpe(v) }, Modifier.weight(1f).heightIn(min = 56.dp)) }
        }
        FieldLabel("TECHNIQUE")
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
            listOf(1 to "Needs work", 2 to "OK", 3 to "Smooth").forEach { (v, label) ->
                TapChip(label, ui.technique == v, { onTechnique(v) }, Modifier.weight(1f))
            }
        }
        BigActionButton(if (block.runAfterM > 0) "Save and on to the run" else if (ui.index + 1 >= ui.blocks.size) "Save and finish" else "Save station", onSave)
    }
}

@Composable
private fun RunBlock(
    ui: HyroxUi, nowMs: Long, block: StationBlock,
    onGo: () -> Unit, onDone: () -> Unit, onSkip: () -> Unit, onMore: () -> Unit, onRpe: (Int) -> Unit,
) {
    val spec = ui.runSpec
    val treadmill = spec?.treadmill == true
    val running = ui.runStartedAt != null
    val elapsed = if (running) ((nowMs - ui.runStartedAt!!) / 1000).toInt().coerceAtLeast(0) else 0
    val target = ui.runTargetSec ?: spec?.takeIf { it.treadmill }?.let { HyroxRuns.targetSec(block.runAfterM, it.speedKmh) }
    Text(if (treadmill) "OPTIONAL TREADMILL RUN" else "OPTIONAL RUN", style = AppType.eyebrow, color = AppColors.textMuted)
    Text(
        if (treadmill) "Treadmill run ${block.runAfterM} m" else "Easy run ${block.runAfterM} m",
        style = MaterialTheme.typography.displaySmall, color = AppColors.textPrimary,
    )
    if (treadmill && spec != null) {
        Column(
            Modifier.fillMaxWidth().clip(AppShapes.medium).background(AppColors.info.copy(alpha = 0.10f))
                .border(1.dp, AppColors.info.copy(alpha = 0.4f), AppShapes.medium).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text("SET THE TREADMILL", style = AppType.eyebrow, color = AppColors.info)
            Text(
                "Set the treadmill to ${HyroxText.speed(spec.speedKmh)} km/h, ${HyroxText.incline(spec.inclinePct)}% incline",
                style = MaterialTheme.typography.titleLarge, color = AppColors.textPrimary,
            )
        }
    }
    Text(
        "Conversational pace, just to get used to running on tired legs. Stop if your right knee flares; skipping is fine.",
        style = MaterialTheme.typography.bodyLarge, color = AppColors.textMuted,
    )
    Column(
        Modifier.fillMaxWidth().clip(AppShapes.large).background(AppColors.surface)
            .border(1.dp, AppColors.outline.copy(alpha = 0.5f), AppShapes.large).padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (treadmill && target != null) {
            val left = (target - elapsed).coerceAtLeast(0)
            Text(if (running) "TIME LEFT" else "READY", style = AppType.eyebrow, color = AppColors.textMuted)
            Text(
                HyroxText.clock(if (running) left else target),
                color = if (running) (if (left in 1..5) AppColors.energy else AppColors.info) else AppColors.textMuted,
                style = AppType.numberHero.copy(fontSize = 96.sp, lineHeight = 100.sp),
            )
            if (running) {
                Box(Modifier.fillMaxWidth().height(6.dp).clip(AppShapes.chip).background(AppColors.track)) {
                    Box(Modifier.fillMaxWidth((elapsed / target.toFloat()).coerceIn(0f, 1f)).height(6.dp).clip(AppShapes.chip).background(AppColors.info))
                }
            }
        } else {
            Text(if (running) "RUN TIME" else "READY", style = AppType.eyebrow, color = AppColors.textMuted)
            Text(HyroxText.clock(elapsed), color = if (running) AppColors.info else AppColors.textMuted, style = AppType.numberHero.copy(fontSize = 96.sp, lineHeight = 100.sp))
        }
        if (running) {
            BigActionButton(if (treadmill) "Done early" else "Done", onDone, Modifier.heightIn(min = 96.dp), brush = AppColors.gradientReward)
            if (treadmill) SecondaryButton("+30 s", onMore, Modifier.fillMaxWidth().heightIn(min = 64.dp))
        } else BigActionButton("Start run", onGo, Modifier.heightIn(min = 96.dp))
    }
    if (running) {
        FieldLabel("EFFORT (OPTIONAL, 6 EASY, 10 ALL OUT)")
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
            (6..10).forEach { v -> SelectChip("$v", ui.runRpe == v, { onRpe(v) }, Modifier.weight(1f).heightIn(min = 56.dp)) }
        }
    }
    SecondaryButton("Skip run", onSkip, Modifier.fillMaxWidth().heightIn(min = 64.dp))
}

// ---------------------------------------------------------------------------------------------
// Finish
// ---------------------------------------------------------------------------------------------

@Composable
private fun HyroxFinish(vm: AppViewModel, ui: HyroxUi) {
    val inputs by vm.inputs.collectAsState()
    HyroxFinishContent(
        ui, minutes = ((vm.hyrox.elapsedMs(ui) + 30_000) / 60_000).toInt().coerceAtLeast(1),
        initialFeeling = inputs.feeling, initialKnee = inputs.knee,
        onSave = { feeling, knee -> vm.saveHyroxSession(feeling, knee) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HyroxFinishContent(ui: HyroxUi, minutes: Int, initialFeeling: Feeling, initialKnee: Int?, onSave: (Feeling, Int?) -> Unit) {
    var burst by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { burst = 1 }
    var feeling by remember { mutableStateOf(initialFeeling) }
    var knee by remember { mutableStateOf(initialKnee) }
    val n = ui.stationCount
    Box(Modifier.fillMaxSize().background(AppColors.background)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Text("Nice work", style = MaterialTheme.typography.displaySmall, color = AppColors.textPrimary)
            Text(
                if (n > 0) "$n ${if (n == 1) "station" else "stations"} in $minutes min. Every session counts." else "Nothing was logged this time, and that is fine.",
                style = MaterialTheme.typography.titleMedium, color = AppColors.textMuted,
            )
            if (n > 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    StatTile("Stations", "$n", Modifier.weight(1f), accent = AppColors.reward, compact = true)
                    StatTile("Time", "$minutes", Modifier.weight(1f), unit = "min", compact = true)
                    if (ui.runs.isNotEmpty()) StatTile(if (ui.runs.size == 1) "Run" else "Runs", "${ui.runs.size}", Modifier.weight(1f), accent = AppColors.info, compact = true)
                }
                SectionCard(title = "What you did") {
                    ui.results.forEach { log ->
                        Text(HyroxText.summary(log), style = MaterialTheme.typography.bodyLarge, color = AppColors.textPrimary)
                    }
                    if (ui.skipped > 0) Text("${ui.skipped} skipped, which is fine.", style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted)
                }
                FieldLabel("HOW DID IT FEEL?")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Feeling.entries.forEach { f ->
                        SelectChip(f.name.lowercase().replaceFirstChar(Char::uppercase), feeling == f, { feeling = f }, Modifier.weight(1f))
                    }
                }
                FieldLabel("RIGHT KNEE AFTERWARDS, 0 (FINE) TO 10 (OPTIONAL)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    (0..10).forEach { k -> SelectChip("$k", knee == k, { knee = if (knee == k) null else k }) }
                }
            }
            BigActionButton(if (n > 0) "Save session" else "Done", { onSave(feeling, knee) })
            Spacer(Modifier.height(Spacing.lg).navigationBarsPadding())
        }
        ConfettiBurst(burst, Modifier.fillMaxSize(), particleCount = 120)
    }
}

/** Equal-width 56dp chip that may wrap to two lines ("Needs work") without clipping. */
@Composable
internal fun TapChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.heightIn(min = 56.dp).clip(AppShapes.chip)
            .background(if (selected) AppColors.primary else AppColors.surfaceRaised)
            .clickable(onClick = onClick).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2,
            color = if (selected) AppColors.onPrimary else AppColors.textPrimary,
        )
    }
}
