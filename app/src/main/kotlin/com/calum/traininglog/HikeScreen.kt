package com.calum.traininglog

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calum.traininglog.core.CueType
import com.calum.traininglog.core.Feeling
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
import kotlinx.coroutines.launch

/*
 * Guided hike / walk: setup (an optional target and the offered next length), a big stopwatch with Pause and Finish, and
 * a finish card for distance and climb. Saved the moment Finish is pressed (see AppViewModel.autosaveHike); the card only
 * edits that record. The quick manual log (LightScreen) is still one tap away for walks already done.
 */

/** "1:32:05" for an hour or more, "32:05" below it. */
internal fun hikeClock(ms: Long): String {
    val s = (ms / 1000).toInt().coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** "1 h 32 min", "45 min". */
internal fun hikeLength(minutes: Int): String = if (minutes >= 60) "${minutes / 60} h ${"%02d".format(minutes % 60)} min" else "$minutes min"

private val TARGETS = listOf(30, 45, 60, 90, 120, 180)

@Composable
fun HikeScreen(vm: AppViewModel, onManual: () -> Unit) {
    val ui by vm.hike.ui.collectAsState()
    when (ui.phase) {
        HikePhase.SETUP -> HikeSetupContent(
            ui,
            onTarget = { vm.hike.setTarget(it) },
            onStart = { vm.hike.start() },
            onManual = onManual,
            onBack = { vm.hikeBack() },
        )
        HikePhase.RUNNING -> HikePlayer(vm, ui)
        HikePhase.DONE -> HikeDoneContent(
            ui,
            onDistance = { vm.hike.adjustDistance(it) }, onClimb = { vm.hike.adjustClimb(it) },
            onFeeling = { vm.hike.setFeeling(it) }, onNote = { vm.hike.setNote(it) }, onDone = { vm.saveHike() },
        )
    }
}

@Composable
private fun HikePlayer(vm: AppViewModel, ui: HikeUi) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cues = remember { CuePlayer(ctx.applicationContext, scope) }
    DisposableEffect(Unit) { onDispose { cues.release() } }
    var elapsedMs by remember { mutableLongStateOf(vm.hike.elapsedMs()) }
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            elapsedMs = vm.hike.elapsedMs()
            val cue = vm.hike.tick()
            if (cue != null) {
                cues.play(if (cue == HikeCue.FINISH) CueType.DONE else CueType.CHANGE)
                flash = true
                scope.launch { delay(600); flash = false }
            }
            delay(250)
        }
    }
    HikeRunningContent(ui, elapsedMs, flash, onTogglePause = { vm.hike.togglePause() }, onFinish = { vm.hike.finish() })
}

// ---------------- Setup ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HikeSetupContent(ui: HikeUi, onTarget: (Int?) -> Unit, onStart: () -> Unit, onManual: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(AppColors.background).verticalScroll(rememberScrollState()).statusBarsPadding().padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text("Hike or walk", style = MaterialTheme.typography.headlineLarge, color = AppColors.textPrimary)
            Text("A big stopwatch. Pause whenever you like.", style = MaterialTheme.typography.titleMedium, color = AppColors.textMuted)
        }
        ui.suggestion?.let { s ->
            SectionCard(title = "Next long walk, if you fancy it") {
                Text(s.text, style = MaterialTheme.typography.bodyLarge, color = AppColors.textPrimary)
                SecondaryButton("Aim for about ${s.minutes} min", { onTarget(s.minutes) }, Modifier.fillMaxWidth(), accent = AppColors.energy)
            }
        }
        Text("TARGET (OPTIONAL)", style = AppType.eyebrow, color = AppColors.textMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SelectChip("No target", ui.targetMin == null, { onTarget(null) }, Modifier.heightIn(min = 48.dp))
            TARGETS.forEach { m -> SelectChip(hikeLength(m), ui.targetMin == m, { onTarget(m) }, Modifier.heightIn(min = 48.dp)) }
        }
        ui.targetMin?.let { t ->
            BigNumberStepper(
                "$t", "min", { onTarget((t - 5).coerceAtLeast(10)) }, { onTarget((t + 5).coerceAtMost(com.calum.traininglog.core.Hike.MAX_MINUTES)) },
                label = "Target length",
            )
            Text(
                if (t >= HikeController.MIN_BEEP_TARGET_MIN) "You get a beep at halfway (${hikeLength(t / 2)}) and another at $t min." else "Targets under 10 minutes have no beeps.",
                style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
            )
        }
        BigActionButton("Start", onStart, Modifier.heightIn(min = 96.dp))
        SecondaryButton("Already done it? Log it", onManual, Modifier.fillMaxWidth())
        SecondaryButton("Back", onBack, Modifier.fillMaxWidth(), accent = AppColors.textMuted)
        Text(
            "The clock keeps counting if the screen locks, so you can pocket the phone. Beeps sound while the app is open. " +
                "When you finish, add the distance and climb if you like. It is saved the moment you press Finish.",
            style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted,
        )
        Spacer(Modifier.height(Spacing.lg))
    }
}

// ---------------- Running ----------------

@Composable
internal fun HikeRunningContent(ui: HikeUi, elapsedMs: Long, flash: Boolean, onTogglePause: () -> Unit, onFinish: () -> Unit) {
    val bg by animateColorAsState(if (flash) AppColors.primary.copy(alpha = 0.22f) else AppColors.background, tween(200), label = "hikeFlash")
    val scope = rememberCoroutineScope()
    var armed by remember { mutableStateOf(false) }
    val target = ui.targetMin
    val targetMs = (target ?: 0) * 60_000L
    Column(
        Modifier.fillMaxSize().background(bg).statusBarsPadding().padding(Spacing.screen).navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (ui.paused) Pill("PAUSED", color = AppColors.energy, filled = true) else Pill("HIKE OR WALK", color = AppColors.primary)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    hikeClock(elapsedMs), maxLines = 1,
                    style = AppType.numberHero.copy(fontSize = 84.sp, lineHeight = 90.sp),
                    color = if (ui.paused) AppColors.textMuted else AppColors.primary,
                )
                Text("moving time", style = MaterialTheme.typography.labelLarge, color = AppColors.textMuted)
                if (target != null) {
                    Box(Modifier.fillMaxWidth().height(14.dp).clip(AppShapes.chip).background(AppColors.track)) {
                        Box(Modifier.fillMaxWidth((elapsedMs / targetMs.toFloat()).coerceIn(0f, 1f)).height(14.dp).clip(AppShapes.chip).background(AppColors.gradientPrimary))
                    }
                    Text(
                        when {
                            elapsedMs >= targetMs -> "Target of ${hikeLength(target)} reached. Carry on or finish whenever you like."
                            elapsedMs >= targetMs / 2 -> "Past halfway. ${hikeLength(((targetMs - elapsedMs) / 60_000).toInt() + 1)} to go."
                            else -> "Target ${hikeLength(target)}. Halfway beep at ${hikeClock(targetMs / 2)}."
                        },
                        style = MaterialTheme.typography.bodyLarge, color = AppColors.textPrimary, textAlign = TextAlign.Center,
                    )
                } else {
                    Text("No target. Finish whenever you are done.", style = MaterialTheme.typography.bodyLarge, color = AppColors.textMuted, textAlign = TextAlign.Center)
                }
            }
        }
        SecondaryButton(if (ui.paused) "Resume" else "Pause", onTogglePause, Modifier.fillMaxWidth().heightIn(min = 72.dp), accent = AppColors.energy)
        BigActionButton(
            if (armed) "Tap again to finish" else "Finish",
            {
                if (armed) onFinish()
                else { armed = true; scope.launch { delay(4000); armed = false } }
            },
            Modifier.fillMaxWidth().heightIn(min = 96.dp), brush = AppColors.gradientReward,
        )
    }
}

// ---------------- Finish ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HikeDoneContent(
    ui: HikeUi,
    onDistance: (Double) -> Unit, onClimb: (Int) -> Unit, onFeeling: (Feeling) -> Unit, onNote: (String) -> Unit, onDone: () -> Unit,
) {
    var burst by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { burst = 1 }
    val minutes = (ui.endedMs / 60_000).toInt()
    BoxWithConstraints(Modifier.fillMaxSize().background(AppColors.background)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Text("Nice one", style = MaterialTheme.typography.displaySmall, color = AppColors.textPrimary)
            Text(
                if (minutes >= 1) "${hikeLength(minutes)} saved. Add the details if you like." else "Under a minute, so nothing will be logged.",
                style = MaterialTheme.typography.titleMedium, color = AppColors.textMuted,
            )
            if (minutes >= 1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    StatTile("Time", hikeLength(minutes), Modifier.weight(1f), accent = AppColors.primary, compact = true)
                    ui.targetMin?.let { StatTile("Target", hikeLength(it), Modifier.weight(1f), accent = AppColors.info, compact = true) }
                }
                BigNumberStepper(
                    ui.distanceKm?.let { fmt(it) } ?: "0", "km", { onDistance(-HikeController.KM_STEP) }, { onDistance(HikeController.KM_STEP) },
                    label = "Distance (optional)",
                )
                BigNumberStepper(
                    "${ui.climbM ?: 0}", "m climb", { onClimb(-HikeController.CLIMB_STEP) }, { onClimb(HikeController.CLIMB_STEP) },
                    label = "Climb (optional)",
                )
                Text("How did that feel? (optional)", style = MaterialTheme.typography.headlineSmall, color = AppColors.textPrimary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    listOf(Feeling.GOOD to "Good", Feeling.OKAY to "Okay", Feeling.ROUGH to "Rough").forEach { (f, label) ->
                        SelectChip(label, ui.feeling == f, { onFeeling(f) }, Modifier.weight(1f).heightIn(min = 56.dp))
                    }
                }
                OutlinedTextField(ui.note, onNote, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
            }
            BigActionButton("Done", onDone, Modifier.heightIn(min = 96.dp))
            Spacer(Modifier.height(Spacing.lg).navigationBarsPadding())
        }
        ConfettiBurst(burst, Modifier.fillMaxSize(), particleCount = 100)
    }
}
