package com.calum.traininglog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.BigActionButton
import com.calum.traininglog.ui.SecondaryButton
import com.calum.traininglog.ui.Spacing

/** One way out of the leave dialog (the "Keep going" button is always added by [LeaveDialog]). */
internal data class LeaveOption(val label: String, val onClick: () -> Unit, val muted: Boolean = false)

/** What system back does on the current screen. */
internal sealed interface BackAction {
    /** An ordinary screen: step back through the stack. */
    data object Pop : BackAction
    /** A finished session or a screen with nothing to lose: do [run] (it navigates itself). */
    data class Do(val run: () -> Unit) : BackAction
    /** A live session: ask first. */
    data class Ask(val text: String, val options: List<LeaveOption>) : BackAction
}

/**
 * Decides what system back (swipe or button) does for the current [screen]. Live sessions never leave silently:
 * they ask, and only an explicit second choice ends or drops anything.
 */
@Composable
internal fun backActionFor(vm: AppViewModel, screen: Screen): BackAction {
    val gym by vm.gym.collectAsState()
    val tread by TreadmillEngine.ui.collectAsState()
    val core by vm.core.ui.collectAsState()
    val guided by vm.guided.ui.collectAsState()
    val knee by vm.kneeCare.ui.collectAsState()
    val hike by vm.hike.ui.collectAsState()
    val hyrox by vm.hyrox.ui.collectAsState()
    val sim by vm.sim.ui.collectAsState()
    val inputs by vm.inputs.collectAsState()
    return when (screen) {
        Screen.Gym -> {
            val g = gym
            val sets = g?.exercises?.sumOf { e -> e.sets.count { it.done } } ?: 0
            when {
                g == null -> BackAction.Do { vm.home() }
                g.finisherOffered -> BackAction.Do { vm.skipFinisher() }
                sets == 0 -> BackAction.Do { vm.discardGym() }
                else -> BackAction.Ask(
                    "You have $sets ${if (sets == 1) "set" else "sets"} logged. Finish and save puts them in your history. " +
                        "Discard drops this session and nothing is saved.",
                    listOf(
                        LeaveOption("Finish and save", { vm.finishGym() }),
                        LeaveOption("Discard", { vm.discardGym() }, muted = true),
                    ),
                )
            }
        }
        Screen.TreadmillPlayer -> when {
            tread.state == null -> BackAction.Do { vm.home() }
            tread.finished -> BackAction.Do { vm.endTreadmill() }
            else -> BackAction.Ask(
                "The treadmill keeps going until you end it. Ending stops the clock and takes you to the finish, " +
                    "where your time so far is saved.",
                listOf(LeaveOption("End session", { vm.endTreadmill() })),
            )
        }
        Screen.TreadmillDone -> BackAction.Do { vm.saveTreadmill(null) } // same as Skip: already saved when it finished
        Screen.Core -> when (core.phase) {
            CorePhase.SETUP -> BackAction.Pop
            CorePhase.RUNNING -> BackAction.Ask(
                "End and save keeps what you have done so far (under a minute is not logged). Discard drops this session.",
                listOf(
                    LeaveOption("End and save", { vm.core.end(); vm.saveCore(null) }),
                    LeaveOption("Discard", { vm.coreDiscard() }, muted = true),
                ),
            )
            CorePhase.DONE -> BackAction.Do { vm.saveCore(null) }
        }
        Screen.KneeCare -> when (knee.phase) {
            KneeCarePhase.SETUP -> BackAction.Do { vm.kneeCareBack() }
            KneeCarePhase.RUNNING -> BackAction.Ask(
                "End and save keeps what you have done so far (under a minute is not logged). Discard drops this session.",
                listOf(
                    LeaveOption("End and save", { vm.kneeCare.end(); vm.saveKneeCare() }),
                    LeaveOption("Discard", { vm.kneeCareDiscard() }, muted = true),
                ),
            )
            KneeCarePhase.DONE -> BackAction.Do { vm.saveKneeCare() }
        }
        is Screen.GuidedTimer -> when (guided.phase) {
            GuidedPhase.SETUP -> BackAction.Do { vm.guidedBack() }
            GuidedPhase.RUNNING -> BackAction.Ask(
                "End and save keeps what you have done so far (under a minute is not logged). Discard drops this session.",
                listOf(
                    LeaveOption("End and save", { vm.guided.end(); vm.saveGuided() }),
                    LeaveOption("Discard", { vm.guidedDiscard() }, muted = true),
                ),
            )
            GuidedPhase.DONE -> BackAction.Do { vm.saveGuided() }
        }
        is Screen.Light -> when (hike.phase) {
            HikePhase.SETUP -> BackAction.Pop
            HikePhase.RUNNING -> BackAction.Ask(
                "End and save keeps the time so far (under a minute is not logged). Discard drops this hike and nothing is saved.",
                listOf(
                    LeaveOption("End and save", { vm.hike.finish(); vm.saveHike() }),
                    LeaveOption("Discard", { vm.hikeDiscard() }, muted = true),
                ),
            )
            HikePhase.DONE -> BackAction.Do { vm.saveHike() }
        }
        is Screen.HyroxSession -> when (hyrox.phase) {
            HyroxPhase.SETUP -> BackAction.Pop
            HyroxPhase.RUNNING -> {
                val progress = hyrox.stationCount > 0 || hyrox.laps.isNotEmpty() || hyrox.step == HyroxStep.RATE
                if (progress) BackAction.Ask(
                    "End and save keeps the stations you have done so far. Discard drops this session and nothing is saved.",
                    listOf(
                        LeaveOption("End and save", { vm.hyrox.endEarly(); vm.saveHyroxSession(inputs.feeling, inputs.knee) }),
                        LeaveOption("Discard", { vm.hyroxDiscard() }, muted = true),
                    ),
                ) else BackAction.Ask(
                    "No station is done yet, so nothing has been logged. Leaving drops this session.",
                    listOf(LeaveOption("Leave", { vm.hyroxDiscard() })),
                )
            }
            HyroxPhase.DONE -> BackAction.Do { vm.saveHyroxSession(inputs.feeling, inputs.knee) }
        }
        is Screen.HyroxSim -> when (sim.phase) {
            SimPhase.SETUP -> BackAction.Do { vm.simClose(toHub = true) }
            SimPhase.RUNNING ->
                if (sim.results.isNotEmpty()) BackAction.Ask(
                    "End and save keeps the legs you have done so far. Discard drops this simulation and nothing is saved.",
                    listOf(
                        LeaveOption("End and save", { vm.sim.endEarly() }),
                        LeaveOption("Discard", { vm.simDiscard() }, muted = true),
                    ),
                ) else BackAction.Ask(
                    "No leg is done yet, so nothing has been logged. Leaving drops this simulation.",
                    listOf(LeaveOption("Leave", { vm.simDiscard() })),
                )
            SimPhase.DONE -> if (sim.viewing) BackAction.Do { vm.simClose(toHub = true) } else BackAction.Do { vm.simFinish() }
        }
        else -> BackAction.Pop
    }
}

/** Calm confirmation shown instead of leaving a live session: "Keep going" is the prominent default. */
@Composable
internal fun LeaveDialog(ask: BackAction.Ask, onKeepGoing: () -> Unit, onChoose: (LeaveOption) -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepGoing,
        containerColor = AppColors.surface,
        title = { Text("Leave this session?", color = AppColors.textPrimary) },
        text = { Text(ask.text, style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted) },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                BigActionButton("Keep going", onKeepGoing, Modifier.fillMaxWidth())
                ask.options.forEach { o ->
                    SecondaryButton(
                        o.label, { onChoose(o) }, Modifier.fillMaxWidth(),
                        accent = if (o.muted) AppColors.textMuted else AppColors.textPrimary,
                    )
                }
            }
        },
    )
}
