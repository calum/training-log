package com.calum.traininglog

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calum.traininglog.core.CueType
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.HyroxDivision
import com.calum.traininglog.core.HyroxSim
import com.calum.traininglog.core.RunMode
import com.calum.traininglog.core.Rewards
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.SimComparison
import com.calum.traininglog.core.SimFormat
import com.calum.traininglog.core.SimLeg
import com.calum.traininglog.core.SimLegKind
import com.calum.traininglog.core.SimPlan
import com.calum.traininglog.core.SimPreset
import com.calum.traininglog.core.SimResult
import com.calum.traininglog.core.TransitionMode
import com.calum.traininglog.core.UserSettings
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

/**
 * Hyrox simulation: setup, player and results behind one route. The state lives in [HyroxSimController]
 * (inside the ViewModel), so rotating or leaving the screen for a moment loses nothing; every clock is monotonic.
 */
@Composable
fun HyroxSimScreen(vm: AppViewModel, preset: SimPreset?) {
    val ui by vm.sim.ui.collectAsState()
    val data by vm.data.collectAsState()
    LaunchedEffect(Unit) { vm.simEnsure(preset) }
    when (ui.phase) {
        SimPhase.SETUP -> HyroxSimSetupContent(
            ui,
            onFormat = { vm.simSetup(format = it) },
            onLoad = { vm.simSetup(loadFactor = it) },
            onMode = { vm.simSetup(runMode = it) },
            onTransition = { vm.simSetTransition(it) },
            onTarget = { on -> if (on) vm.simStepTarget(0) else vm.simSetup(clearTarget = true) },
            onStep = { vm.simStepTarget(it) },
            onStart = { vm.sim.start() },
            onBack = { vm.simClose(toHub = true) },
        )
        SimPhase.RUNNING -> HyroxSimPlayer(vm, ui)
        SimPhase.DONE -> HyroxSimResults(vm, ui, data.records, data.profile.settings)
    }
}

// ---------------------------------------------------------------------------------------------
// Setup
// ---------------------------------------------------------------------------------------------

private fun legsSummary(plan: SimPlan): String {
    val min = plan.estimatedMinutes
    val t = if (min >= 60) "${min / 60} h ${"%02d".format(min % 60)} min" else "$min min"
    return "${plan.raceLegs.size} legs + ${plan.transitionCount} transitions, about $t"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HyroxSimSetupContent(
    ui: HyroxSimUi,
    onFormat: (SimFormat) -> Unit,
    onLoad: (Double) -> Unit,
    onMode: (RunMode) -> Unit,
    onTransition: (Int) -> Unit,
    onTarget: (Boolean) -> Unit,
    onStep: (Int) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    val plan = ui.plan
    Column(
        Modifier.fillMaxSize().background(AppColors.background).verticalScroll(rememberScrollState())
            .statusBarsPadding().padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text("Hyrox simulation", style = MaterialTheme.typography.headlineLarge, color = AppColors.textPrimary)
            Text(
                if (plan != null) legsSummary(plan) else "Pick a format",
                style = MaterialTheme.typography.titleMedium, color = AppColors.textMuted,
            )
        }
        Column(
            Modifier.fillMaxWidth().clip(AppShapes.medium).background(AppColors.info.copy(alpha = 0.10f))
                .border(1.dp, AppColors.info.copy(alpha = 0.4f), AppShapes.medium).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text("A REHEARSAL, NOT A RACE", style = AppType.eyebrow, color = AppColors.info)
            Text(
                "Aim for RPE 7-8. Run, station, run, station, in race order. How it goes is information for next time, never a test.",
                style = MaterialTheme.typography.bodyLarge, color = AppColors.textPrimary,
            )
        }

        FieldLabel("FORMAT")
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
            SimFormat.entries.forEach { f -> SelectChip(when (f) { SimFormat.FULL -> "Full"; SimFormat.FIRST_HALF -> "1st half"; SimFormat.SECOND_HALF -> "2nd half" }, ui.format == f, { onFormat(f) }, Modifier.weight(1f)) }
        }
        Text(
            when (ui.format) {
                SimFormat.FULL -> "All eight stations, 8 run legs."
                SimFormat.FIRST_HALF -> "Stations 1-4 (SkiErg to burpee broad jumps) with 4 run legs."
                SimFormat.SECOND_HALF -> "Stations 5-8 (rowing to wall balls) with 4 run legs."
            },
            style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
        )

        FieldLabel("LOADS")
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
            listOf(1.0 to "Race loads", 0.9 to "90%", 0.8 to "80%").forEach { (f, label) ->
                SelectChip(label, kotlin.math.abs(ui.loadFactor - f) < 0.001, { onLoad(f) }, Modifier.weight(1f))
            }
        }
        Text(
            if (ui.loadFactor >= 0.999) "Race standard for ${ui.division.label}, with any weights you edited." else "Lighter first attempt: weights scaled and rounded to 2.5 kg. Reps and distances stay standard.",
            style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
        )

        FieldLabel("RUN LEGS")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            RunMode.entries.forEach { m -> SelectChip(m.label, ui.runMode == m, { onMode(m) }) }
        }
        Column(
            Modifier.fillMaxWidth().clip(AppShapes.medium).background(AppColors.surfaceRaised).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(HyroxSim.runModeNote(ui.runMode, ui.stage), style = MaterialTheme.typography.bodyMedium, color = AppColors.textPrimary)
            Text(
                "Knee-safe swaps for a 1 km run: incline walk, rower or SkiErg 1000 m, bike 3 km. Your knee stage: ${ui.stage.label.lowercase()}.",
                style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
            )
        }

        FieldLabel("TRANSITIONS (THE ROXZONE)")
        val allowance = plan?.transitionSec ?: HyroxSim.DEFAULT_TRANSITION_SEC
        var customOn by remember { mutableStateOf(false) }
        val customSelected = customOn || TransitionMode.of(allowance) == TransitionMode.CUSTOM
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            TransitionMode.entries.forEach { m ->
                val sec = m.seconds
                val selected = if (m == TransitionMode.CUSTOM) customSelected else !customSelected && sec == allowance
                SelectChip(if (sec != null) "${m.label} · $sec s" else m.label, selected, {
                    if (sec != null) { customOn = false; onTransition(sec) } else customOn = true
                })
            }
        }
        if (customSelected) BigNumberStepper("$allowance", "s", { onTransition(allowance - HyroxSim.TRANSITION_STEP) }, { onTransition(allowance + HyroxSim.TRANSITION_STEP) }, label = "Time for each transition")
        Text(
            "Time to load the sled, fetch the wall ball or reset the monitor between legs. The clock never stops, like a real race, " +
                "and the transition time counts in your total. Going over is fine: it is just counted.",
            style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
        )

        FieldLabel("TARGET FINISH TIME (OPTIONAL)")
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
            SelectChip("No target", ui.targetSec == null, { onTarget(false) }, Modifier.weight(1f))
            SelectChip("Set a target", ui.targetSec != null, { onTarget(true) }, Modifier.weight(1f))
        }
        val target = ui.targetSec
        if (target != null) {
            BigNumberStepper("${target / 60}", "min", { onStep(-1) }, { onStep(1) }, label = "Finish time")
            Text(
                "Split times are ESTIMATES from the usual proportions of a Hyrox (running about 55-60% of the time). A guide for pacing, never a pass mark.",
                style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
            )
        } else {
            Text(
                "No target means no times to chase. You get effort guidance instead: first run easier than you think, RPE 6-7 through the first half, nothing all-out until the last two legs.",
                style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
            )
        }

        FieldLabel("THE LEGS")
        if (plan != null) {
            SectionCard {
                plan.raceLegs.forEach { leg -> SetupLegRow(plan, leg) }
            }
            plan.notes.drop(2).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted) }
        }

        BigActionButton("Start", onStart, enabled = plan != null && plan.legs.isNotEmpty())
        SecondaryButton("Back", onBack, Modifier.fillMaxWidth(), accent = AppColors.textMuted)
        Text(
            "A 3-2-1 beep starts leg 1. Tap Done at the end of each leg: the next one starts straight away, like the real thing. " +
                "Treadmill run legs count down for you. The clocks keep counting if you glance at another app.",
            style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
        )
        Spacer(Modifier.height(Spacing.lg).navigationBarsPadding())
    }
}

@Composable
private fun SetupLegRow(plan: SimPlan, leg: SimLeg) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text("${leg.raceIndex + 1}", style = AppType.numberS, color = if (leg.kind == SimLegKind.RUN) AppColors.info else AppColors.primary, modifier = Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            val title = if (leg.kind == SimLegKind.RUN) "Run leg · ${(leg.runMode ?: plan.runMode).label} ${(leg.runMode ?: plan.runMode).distanceText}" else leg.station!!.displayName
            Text(title, style = MaterialTheme.typography.titleSmall, color = AppColors.textPrimary)
            if (leg.kind == SimLegKind.STATION) Text(HyroxSim.legLoadText(leg), style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
        }
        val est = leg.targetSec ?: if (leg.kind == SimLegKind.STATION) HyroxSim.typicalStationSec(leg.station!!) else null
        if (est != null) Text((if (leg.targetSec == null) "~" else "~") + HyroxText.clock(est), style = MaterialTheme.typography.labelLarge, color = AppColors.textMuted)
    }
}

// ---------------------------------------------------------------------------------------------
// Player
// ---------------------------------------------------------------------------------------------

@Composable
private fun HyroxSimPlayer(vm: AppViewModel, ui: HyroxSimUi) {
    KeepScreenOn()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cues = remember { CuePlayer(ctx.applicationContext, scope) }
    DisposableEffect(Unit) { onDispose { cues.release() } }

    var nowMs by remember { mutableLongStateOf(vm.sim.nowMs()) }
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        var warnedLeg = -1
        var lastLead = 0
        var lastTransition = -1
        while (true) {
            nowMs = vm.sim.nowMs()
            val u = vm.sim.ui.value
            // 3-2-1 before the first leg: one beep a second, then the change cue plays when the leg begins.
            val lead = HyroxSimController.leadInLeftAt(u, nowMs)
            if (lead in 1..3 && lead != lastLead) { lastLead = lead; cues.play(CueType.PRE_WARNING) }
            if (lead == 0) lastLead = 0
            // Treadmill countdown: a pre-warning three seconds out.
            val target = u.countdownSec
            if (target != null && u.pausedAt == null && lead == 0) {
                val left = target - HyroxSimController.legSecAt(u, nowMs)
                val key = u.legIndex * 1000 + u.extraSec
                if (left in 1..3 && warnedLeg != key) { warnedLeg = key; cues.play(CueType.PRE_WARNING) }
            }
            // A transition: a pre-warning 10 s before its allowance ends, a 3-2-1 beep, and the change cue at zero.
            val tl = u.leg
            if (tl != null && tl.kind == SimLegKind.TRANSITION && u.pausedAt == null && lead == 0) {
                val left = (tl.allowanceSec ?: 0) - HyroxSimController.legSecAt(u, nowMs)
                val key = u.legIndex * 1000 + left
                if (left >= 0 && key != lastTransition) {
                    lastTransition = key
                    if (left == 10 || left in 1..3) cues.play(CueType.PRE_WARNING) else if (left == 0) cues.play(CueType.CHANGE)
                }
            }
            vm.sim.tick()
            delay(200)
        }
    }
    // Every leg change: flash, and the done fanfare after a countdown ended by itself (else the double beep).
    LaunchedEffect(ui.changes) {
        if (ui.changes > 0) {
            cues.play(if (ui.autoAdvanced) CueType.DONE else CueType.CHANGE)
            flash = true; delay(800); flash = false
        }
    }
    // The leg timer starts after the lead-in: give it the change cue too.
    LaunchedEffect(ui.startedAt) {
        val wait = ui.startedAt - vm.sim.nowMs()
        if (wait > 0) { delay(wait); cues.play(CueType.CHANGE); flash = true; delay(800); flash = false }
    }

    HyroxSimPlayerBody(
        ui, nowMs, flash,
        onDone = { vm.sim.legDone() }, onSkip = { vm.sim.skipLeg() },
        onPause = { if (ui.pausedAt == null) vm.sim.pause() else vm.sim.resume() },
        onMore = { vm.sim.addTime() }, onRate = { i, v -> vm.sim.rate(i, v) }, onEnd = { vm.sim.endEarly() },
    )
}

@Composable
internal fun HyroxSimPlayerBody(
    ui: HyroxSimUi,
    nowMs: Long,
    flash: Boolean,
    onDone: () -> Unit,
    onSkip: () -> Unit,
    onPause: () -> Unit,
    onMore: () -> Unit,
    onRate: (Int, Int) -> Unit,
    onEnd: () -> Unit,
) {
    val plan = ui.plan ?: return
    val leg = ui.leg ?: return
    val paused = ui.pausedAt != null
    val raceSec = HyroxSimController.clockSec(ui, nowMs)
    val legSec = HyroxSimController.legSecAt(ui, nowMs)
    val lead = HyroxSimController.leadInLeftAt(ui, nowMs)
    val flashBg by animateColorAsState(if (flash) AppColors.primary.copy(alpha = 0.22f) else AppColors.background, tween(200), label = "simFlash")
    val countdown = ui.countdownSec

    Column(Modifier.fillMaxSize().background(flashBg).statusBarsPadding()) {
        // Race clock and the segmented progress through the legs.
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Column(Modifier.weight(1f)) {
                    Text("RACE CLOCK", style = AppType.eyebrow, color = AppColors.textMuted)
                    Text(HyroxSimController.longClock(raceSec), style = AppType.numberM, color = if (paused) AppColors.textMuted else AppColors.textPrimary)
                }
                if (paused) Pill("Paused", color = AppColors.info)
                Text(
                    if (leg.kind == SimLegKind.TRANSITION) "After leg ${leg.index / 2 + 1} of ${plan.raceLegs.size}" else "Leg ${leg.raceIndex + 1} of ${plan.raceLegs.size}",
                    style = MaterialTheme.typography.labelLarge, color = AppColors.textMuted,
                )
            }
            SegmentBar(ui, plan)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(HyroxSim.legTitle(plan, leg), style = MaterialTheme.typography.headlineMedium, color = AppColors.textPrimary)
            if (leg.kind == SimLegKind.TRANSITION) {
                TransitionPanel(leg, legSec, paused, onDone)
            } else {
                Text(legDetail(leg), style = MaterialTheme.typography.titleMedium, color = AppColors.primary)
                if (leg.kind == SimLegKind.RUN && leg.runMode == RunMode.TREADMILL) {
                    leg.speedKmh?.let { kmh ->
                        Column(
                            Modifier.fillMaxWidth().clip(AppShapes.medium).background(AppColors.info.copy(alpha = 0.10f))
                                .border(1.dp, AppColors.info.copy(alpha = 0.4f), AppShapes.medium).padding(Spacing.lg),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            Text("SET THE TREADMILL", style = AppType.eyebrow, color = AppColors.info)
                            Text("Set the treadmill to ${HyroxText.speed(kmh)} km/h", style = MaterialTheme.typography.titleLarge, color = AppColors.textPrimary)
                        }
                    }
                }
                Text(leg.tip, style = MaterialTheme.typography.bodyLarge, color = AppColors.textMuted, maxLines = 2)

                LegTimer(leg, countdown, legSec, lead, paused, onDone, onMore)
            }

            val nextRace = if (leg.kind == SimLegKind.TRANSITION) null else plan.legs.drop(ui.legIndex + 1).firstOrNull { it.kind != SimLegKind.TRANSITION }
            nextRace?.let { next ->
                Row(
                    Modifier.fillMaxWidth().clip(AppShapes.medium).background(AppColors.surfaceRaised).padding(Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Text("NEXT", style = AppType.eyebrow, color = AppColors.textMuted)
                    Column(Modifier.weight(1f)) {
                        Text(HyroxSim.legTitle(plan, next), style = MaterialTheme.typography.titleSmall, color = AppColors.textPrimary)
                        val detail = legDetail(next)
                        if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
                    }
                }
            } ?: if (leg.kind != SimLegKind.TRANSITION) Text("This is the last leg. Finish steady, and strong if you feel good.", style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted) else Unit

            val ri = ui.ratingIndex
            if (ri != null && nowMs - ui.ratingAt < HyroxSimController.RATING_WINDOW_MS) {
                val current = ui.results.firstOrNull { it.index == ri }?.rpe
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FieldLabel("HOW DID LEG ${ri + 1} FEEL? (OPTIONAL, 6 EASY, 10 ALL OUT)")
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                        (6..10).forEach { v -> SelectChip("$v", current == v, { onRate(ri, v) }, Modifier.weight(1f).heightIn(min = 56.dp)) }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                SecondaryButton(if (paused) "Resume" else "Pause", onPause, Modifier.weight(1f).heightIn(min = 64.dp), accent = AppColors.info)
                SecondaryButton(if (leg.kind == SimLegKind.TRANSITION) "Skip the wait" else "Skip leg", onSkip, Modifier.weight(1f).heightIn(min = 64.dp), enabled = !paused)
            }
            SecondaryButton("End early and save", onEnd, Modifier.fillMaxWidth().heightIn(min = 64.dp), accent = AppColors.textMuted)
            Spacer(Modifier.height(Spacing.lg).navigationBarsPadding())
        }
    }
}

/** "50 m · 152 kg · about 2:30", "about 6:24 · 9.4 km/h" or "1000 m": the load and the (estimated) target of a leg. */
private fun legDetail(leg: SimLeg): String {
    val parts = mutableListOf<String>()
    if (leg.kind == SimLegKind.STATION) parts += HyroxSim.legLoadText(leg)
    leg.targetSec?.let { parts += (if (leg.runMode == RunMode.TREADMILL) "" else "about ") + HyroxText.clock(it) }
    leg.speedKmh?.let { parts += "${HyroxText.speed(it)} km/h" }
    return parts.filter { it.isNotBlank() }.joinToString(" · ")
}

@Composable
private fun SegmentBar(ui: HyroxSimUi, plan: SimPlan) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        val current = if (ui.leg?.kind == SimLegKind.TRANSITION) ui.legIndex + 1 else ui.legIndex
        plan.raceLegs.forEach { l ->
            val r = ui.results.firstOrNull { it.index == l.index }
            val color = when {
                r != null && r.skipped -> AppColors.textMuted.copy(alpha = 0.45f)
                r != null -> AppColors.primary
                l.index == current -> AppColors.info
                else -> AppColors.track
            }
            Box(Modifier.weight(1f).height(8.dp).clip(AppShapes.chip).background(color))
        }
    }
}

/** The transition between two legs: what to do next, a calm countdown of the allowance, and "Ready, go". */
@Composable
private fun TransitionPanel(leg: SimLeg, legSec: Int, paused: Boolean, onReady: () -> Unit) {
    val allowance = leg.allowanceSec ?: 0
    val left = allowance - legSec
    val over = left < 0
    Column(
        Modifier.fillMaxWidth().clip(AppShapes.medium).background(AppColors.info.copy(alpha = 0.10f))
            .border(1.dp, AppColors.info.copy(alpha = 0.4f), AppShapes.medium).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text("GET READY", style = AppType.eyebrow, color = AppColors.info)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Box(Modifier.width(26.dp).height(26.dp).border(2.dp, AppColors.info, AppShapes.small))
            Text(leg.prep, style = MaterialTheme.typography.titleLarge, color = AppColors.textPrimary, modifier = Modifier.weight(1f))
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(AppShapes.large).background(AppColors.surface)
            .border(1.dp, AppColors.outline.copy(alpha = 0.5f), AppShapes.large).padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (over) {
            Text("OVER THE ALLOWANCE, THE CLOCK KEEPS GOING", style = AppType.eyebrow, color = AppColors.textMuted, textAlign = TextAlign.Center)
            Text("+${HyroxText.clock(-left)}", style = AppType.numberHero.copy(fontSize = 96.sp, lineHeight = 100.sp), color = AppColors.energy)
        } else {
            Text("TIME LEFT", style = AppType.eyebrow, color = AppColors.textMuted)
            Text(HyroxText.clock(left), style = AppType.numberHero.copy(fontSize = 96.sp, lineHeight = 100.sp), color = AppColors.info)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(AppShapes.chip).background(AppColors.track)) {
                Box(Modifier.fillMaxWidth((legSec / allowance.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)).height(6.dp).clip(AppShapes.chip).background(AppColors.info))
            }
        }
        BigActionButton("Ready, go", onReady, Modifier.heightIn(min = 96.dp), enabled = !paused, brush = AppColors.gradientReward)
    }
    Text(
        "The race clock keeps running and this time counts in your total. Going over is just counted, never a penalty.",
        style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
    )
}

@Composable
private fun LegTimer(
    leg: SimLeg, countdown: Int?, legSec: Int, lead: Int, paused: Boolean, onDone: () -> Unit, onMore: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(AppShapes.large).background(AppColors.surface)
            .border(1.dp, AppColors.outline.copy(alpha = 0.5f), AppShapes.large).padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (lead > 0) {
            Text("GET READY", style = AppType.eyebrow, color = AppColors.textMuted)
            Text("$lead", style = AppType.numberHero.copy(fontSize = 96.sp, lineHeight = 100.sp), color = AppColors.primary)
        } else if (countdown != null) {
            val left = (countdown - legSec).coerceAtLeast(0)
            Text("TIME LEFT", style = AppType.eyebrow, color = AppColors.textMuted)
            Text(
                HyroxText.clock(left), style = AppType.numberHero.copy(fontSize = 96.sp, lineHeight = 100.sp),
                color = if (left in 1..5) AppColors.energy else AppColors.info,
            )
            Box(Modifier.fillMaxWidth().height(6.dp).clip(AppShapes.chip).background(AppColors.track)) {
                Box(Modifier.fillMaxWidth((legSec / countdown.toFloat()).coerceIn(0f, 1f)).height(6.dp).clip(AppShapes.chip).background(AppColors.info))
            }
        } else {
            Text("LEG TIME", style = AppType.eyebrow, color = AppColors.textMuted)
            Text(HyroxText.clock(legSec), style = AppType.numberHero.copy(fontSize = 96.sp, lineHeight = 100.sp), color = if (paused) AppColors.textMuted else AppColors.primary)
        }
        if (leg.reps != null && lead == 0 && countdown == null) {
            Text("Do ${leg.reps} reps, then press Done", style = MaterialTheme.typography.headlineSmall, color = AppColors.textPrimary, textAlign = TextAlign.Center)
        }
        BigActionButton(
            if (countdown != null) "Done early" else "Done", onDone, Modifier.heightIn(min = 96.dp),
            enabled = !paused && lead == 0, brush = AppColors.gradientReward,
        )
        if (countdown != null) SecondaryButton("+10 s", onMore, Modifier.fillMaxWidth().heightIn(min = 64.dp), enabled = !paused)
    }
}

// ---------------------------------------------------------------------------------------------
// Results
// ---------------------------------------------------------------------------------------------

@Composable
private fun HyroxSimResults(vm: AppViewModel, ui: HyroxSimUi, records: List<SessionRecord>, settings: UserSettings) {
    val inputs by vm.inputs.collectAsState()
    val id = ui.savedId ?: ui.viewId
    val record = records.firstOrNull { it.id == id }
    val division = settings.hyroxDivision
    val result = record?.let { SimResult.from(it, division, settings) }
    val previous = record?.let { r -> SimResult.previousOf(records, r)?.let { SimResult.from(it, division, settings) } }
    val celebration = if (record != null && !ui.viewing) Rewards.celebration(record, records.filter { it.id != record.id }, settings) else emptyList()
    HyroxSimResultsContent(
        result = result, previous = previous, celebration = celebration, division = division,
        editable = !ui.viewing, endedEarly = ui.endedEarly,
        initialFeeling = record?.feeling ?: inputs.feeling, initialKnee = record?.kneePain ?: inputs.knee,
        lastRpe = record?.stations?.lastOrNull()?.rpe,
        onSave = { f, k, r -> vm.saveSim(f, k, r) },
        onBack = { vm.simClose(toHub = ui.viewing) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HyroxSimResultsContent(
    result: SimResult?,
    previous: SimResult?,
    celebration: List<String>,
    division: HyroxDivision,
    editable: Boolean,
    endedEarly: Boolean,
    initialFeeling: Feeling,
    initialKnee: Int?,
    lastRpe: Int?,
    onSave: (Feeling, Int?, Int?) -> Unit,
    onBack: () -> Unit,
) {
    var burst by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { if (editable) burst = 1 }
    var feeling by remember { mutableStateOf(initialFeeling) }
    var knee by remember { mutableStateOf(initialKnee) }
    var lastEffort by remember { mutableStateOf(lastRpe) }
    Box(Modifier.fillMaxSize().background(AppColors.background)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            if (result == null) {
                Text("Simulation", style = MaterialTheme.typography.displaySmall, color = AppColors.textPrimary)
                Text(
                    "Nothing was logged this time, and that is fine. A simulation is there whenever you want it.",
                    style = MaterialTheme.typography.titleMedium, color = AppColors.textMuted,
                )
                BigActionButton("Done", onBack)
                return@Column
            }
            val meta = result.meta
            val comparison = SimResult.compare(result, previous)
            Text(if (editable) "Nice work" else "Simulation", style = MaterialTheme.typography.displaySmall, color = AppColors.textPrimary)
            Text(
                "${meta.format.label} simulation · ${result.legsDone} of ${result.legs.size} legs" +
                    if (endedEarly || meta.endedEarly) ", ended early. What you did counts." else ".",
                style = MaterialTheme.typography.titleMedium, color = AppColors.textMuted,
            )

            // Total time, big.
            Column(
                Modifier.fillMaxWidth().clip(AppShapes.large).background(AppColors.surface)
                    .border(1.dp, AppColors.outline.copy(alpha = 0.5f), AppShapes.large).padding(Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text("TOTAL TIME", style = AppType.eyebrow, color = AppColors.textMuted)
                Text(HyroxSimController.longClock(result.totalSec), style = AppType.numberHero.copy(fontSize = 84.sp, lineHeight = 88.sp), color = AppColors.primary)
                Text(
                    "Run legs: ${meta.runMode.label.lowercase()} · loads ${Math.round(meta.loadFactor * 100)}%",
                    style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted, textAlign = TextAlign.Center,
                )
                meta.targetSec?.let {
                    Text("Estimate used for the splits: ${HyroxSimController.longClock(it)}", style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
                }
            }

            if (celebration.isNotEmpty()) {
                SectionCard {
                    celebration.forEach { Text(it, style = MaterialTheme.typography.bodyLarge, color = AppColors.textPrimary) }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                val runShare = result.runShare
                StatTile("Running", HyroxSimController.longClock(result.runSec), Modifier.weight(1f), accent = AppColors.info, compact = true,
                    unit = runShare?.let { "${Math.round(it * 100)}%" } ?: "")
                StatTile("Stations", HyroxSimController.longClock(result.stationSec), Modifier.weight(1f), accent = AppColors.reward, compact = true,
                    unit = result.stationShare?.let { "${Math.round(it * 100)}%" } ?: "")
                if (result.roxCount > 0) {
                    StatTile("Roxzone", HyroxSimController.longClock(result.roxSec), Modifier.weight(1f), accent = AppColors.energy, compact = true,
                        unit = result.roxShare?.let { "${Math.round(it * 100)}%" } ?: "")
                }
            }

            ComparisonCard(comparison, previous, result)

            SectionCard(title = "Splits") { SplitsTable(result) }

            SectionCard(title = "Leg times") {
                LegBars(result)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    LegendDot(AppColors.info, "Run legs"); LegendDot(AppColors.primary, "Stations")
                }
            }

            result.workOn()?.let { tip ->
                SectionCard(title = "Something to work on") { Text(tip, style = MaterialTheme.typography.bodyLarge, color = AppColors.textPrimary) }
            }

            if (editable) {
                if (result.legs.any { it.done }) {
                    FieldLabel("EFFORT OF THE LAST LEG (OPTIONAL, 6 EASY, 10 ALL OUT)")
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                        (6..10).forEach { v -> SelectChip("$v", lastEffort == v, { lastEffort = v }, Modifier.weight(1f).heightIn(min = 56.dp)) }
                    }
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
                BigActionButton("Save", { onSave(feeling, knee, lastEffort) })
                Text("It is already saved: this only adds how it felt.", style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
            } else {
                BigActionButton("Back", onBack)
            }
            Spacer(Modifier.height(Spacing.lg).navigationBarsPadding())
        }
        ConfettiBurst(burst, Modifier.fillMaxSize(), particleCount = 120)
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Box(Modifier.width(14.dp).height(14.dp).clip(AppShapes.chip).background(color))
        Text(text, style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
    }
}

@Composable
private fun ComparisonCard(c: SimComparison, previous: SimResult?, result: SimResult) {
    SectionCard(title = if (previous != null) "Against last time" else "Your starting point") {
        Text(c.totalText, style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary)
        if (c.transitionText.isNotBlank()) Text(c.transitionText, style = MaterialTheme.typography.bodyMedium, color = AppColors.textPrimary)
        if (c.shareText.isNotBlank()) Text(c.shareText, style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted)
        // The two biggest gains, if any: a positive way to read a comparison.
        val wins = c.legs.filter { it.deltaSec < 0 }.sortedBy { it.deltaSec }.take(2)
        wins.forEach { w ->
            val leg = result.legs[w.index]
            val name = if (leg.kind == SimLegKind.RUN) "Run leg ${w.index / 2 + 1}" else leg.station!!.displayName
            Text("$name: ${w.text}", style = MaterialTheme.typography.bodyMedium, color = AppColors.reward)
        }
        if (previous == null) Text("Next time you will see how it compares, leg by leg.", style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
    }
}

@Composable
private fun SplitsTable(result: SimResult) {
    val hasTarget = result.legs.any { it.targetSec != null }
    var showTransitions by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text("LEG", style = AppType.eyebrow, color = AppColors.textMuted, modifier = Modifier.weight(1.6f))
            Text("TIME", style = AppType.eyebrow, color = AppColors.textMuted, modifier = Modifier.weight(0.8f), textAlign = TextAlign.End)
            if (hasTarget) {
                Text("EST.", style = AppType.eyebrow, color = AppColors.textMuted, modifier = Modifier.weight(0.8f), textAlign = TextAlign.End)
                Text("VS EST.", style = AppType.eyebrow, color = AppColors.textMuted, modifier = Modifier.weight(1.2f), textAlign = TextAlign.End)
            }
        }
        result.legs.forEach { l ->
            val name = if (l.kind == SimLegKind.RUN) "Run ${l.index / 2 + 1}" else "${l.station!!.displayName}"
            Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "${l.index + 1}  $name", style = MaterialTheme.typography.bodyMedium,
                    color = if (l.done) AppColors.textPrimary else AppColors.textMuted, modifier = Modifier.weight(1.6f), maxLines = 1,
                )
                Text(
                    l.seconds?.let { HyroxText.clock(it) } ?: "skipped", style = MaterialTheme.typography.bodyMedium,
                    color = if (l.done) (if (l.kind == SimLegKind.RUN) AppColors.info else AppColors.primary) else AppColors.textMuted,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.8f), textAlign = TextAlign.End,
                )
                if (hasTarget) {
                    Text(l.targetSec?.let { HyroxText.clock(it) } ?: "", style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted, modifier = Modifier.weight(0.8f), textAlign = TextAlign.End)
                    val sec = l.seconds
                    val est = l.targetSec
                    val delta = if (sec != null && est != null) SimResult.deltaText(sec - est, withReassurance = false) else ""
                    Text(delta, style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted, modifier = Modifier.weight(1.2f), textAlign = TextAlign.End, maxLines = 1)
                }
            }
        }
        if (result.roxCount > 0) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(AppShapes.small).clickable { showTransitions = !showTransitions },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    "Roxzone (${result.roxCount})${if (showTransitions) " ▴" else " ▾"}", style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.energy, modifier = Modifier.weight(1.6f), maxLines = 1,
                )
                Text(HyroxText.clock(result.roxSec), style = MaterialTheme.typography.bodyMedium, color = AppColors.energy, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.8f), textAlign = TextAlign.End)
                if (hasTarget) Spacer(Modifier.weight(2.0f))
            }
            if (showTransitions) {
                result.transitions.forEachIndexed { k, sec ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 26.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text("   After leg ${k + 1}", style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted, modifier = Modifier.weight(1.6f))
                        Text(sec?.let { HyroxText.clock(it) } ?: "-", style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted, modifier = Modifier.weight(0.8f), textAlign = TextAlign.End)
                        if (hasTarget) Spacer(Modifier.weight(2.0f))
                    }
                }
            }
        }
    }
}

/** A simple bar per leg (run legs blue, stations mint), tallest bar = the slowest leg; the estimate is a faint outline. */
@Composable
private fun LegBars(result: SimResult) {
    val max = result.legs.maxOf { maxOf(it.seconds ?: 0, it.targetSec ?: 0) }.coerceAtLeast(1)
    val barArea = 120.dp
    Row(Modifier.fillMaxWidth().height(barArea + 20.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        result.legs.forEach { l ->
            val color = if (l.kind == SimLegKind.RUN) AppColors.info else AppColors.primary
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Box(Modifier.fillMaxWidth().height(barArea), contentAlignment = Alignment.BottomCenter) {
                    l.targetSec?.let { t ->
                        Box(Modifier.fillMaxWidth().height(barArea * (t / max.toFloat())).clip(AppShapes.small).border(1.dp, color.copy(alpha = 0.45f), AppShapes.small))
                    }
                    l.seconds?.let { s ->
                        Box(Modifier.fillMaxWidth().height((barArea * (s / max.toFloat())).coerceAtLeast(3.dp)).clip(AppShapes.small).background(color))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("${l.index + 1}", style = MaterialTheme.typography.labelSmall, color = AppColors.textMuted, maxLines = 1)
            }
        }
    }
}
