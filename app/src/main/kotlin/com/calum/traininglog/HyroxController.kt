package com.calum.traininglog

import android.os.SystemClock
import com.calum.traininglog.core.HyroxDivision
import com.calum.traininglog.core.HyroxSessionPlan
import com.calum.traininglog.core.HyroxStation
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.StationBlock
import com.calum.traininglog.core.StationKind
import com.calum.traininglog.core.StationLog
import com.calum.traininglog.core.StationPrescription
import com.calum.traininglog.core.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/*
 * Guided Hyrox station session: setup, player and finish behind one route (see HyroxSessionScreen).
 *
 * All the state lives here, in plain data, so it survives recomposition, rotation and leaving the screen for a
 * moment. Every time stamp is on a monotonic clock (default SystemClock.elapsedRealtime; tests inject a fake
 * one), so a lap or a rest keeps counting correctly while the app is in the background.
 *
 * One station block = [StationPrescription.rounds] laps. Per lap: READY -> LAP (Go ... Done) -> REST (the
 * prescription's rest seconds, with a beep) -> READY of the next round. After the last lap: RATE (correct what you
 * actually did, effort, technique) -> optional easy RUN -> the next block, or DONE.
 */

enum class HyroxPhase { SETUP, RUNNING, DONE }
enum class HyroxStep { READY, LAP, REST, RATE, RUN }

/** What the planner needs, read from the app data by the ViewModel. */
data class HyroxPlanInput(val records: List<SessionRecord>, val division: HyroxDivision, val settings: UserSettings) {
    /** Latest knee score on record, or null. */
    val lastKnee: Int? get() = records.filter { it.kneePain != null }.sortedWith(compareBy({ it.date }, { it.id })).lastOrNull()?.kneePain

    /** Easy runs are only offered while the last knee score is below 4. */
    val runsAllowed: Boolean get() = lastKnee.let { it == null || it < KNEE_RUN_LIMIT }

    companion object { const val KNEE_RUN_LIMIT = 4 }
}

/** What the user actually did in a block: totals over all rounds. Null where the station has no such number. */
data class HyroxActual(val weightKg: Double?, val distanceM: Int?, val reps: Int?)

data class HyroxUi(
    val minutes: Int = 45,
    val focus: Set<HyroxStation> = emptySet(),
    val withRuns: Boolean = false,
    /** Stations left out of the plan by "Different mix" (cleared when the focus changes). */
    val excluded: Set<HyroxStation> = emptySet(),
    /** The focus the screen was opened with; tells the screen when to prepare afresh. */
    val entryFocus: Set<HyroxStation>? = null,
    val plan: HyroxSessionPlan? = null,
    val phase: HyroxPhase = HyroxPhase.SETUP,
    // ---- running ----
    val blocks: List<StationBlock> = emptyList(),
    val index: Int = 0,
    val step: HyroxStep = HyroxStep.READY,
    /** 0-based round inside the current block. */
    val round: Int = 0,
    /** Lap seconds finished in the current block. */
    val laps: List<Int> = emptyList(),
    val lapStartedAt: Long? = null,
    val restEndsAt: Long = 0L,
    val restTotalSec: Int = 0,
    val actual: HyroxActual = HyroxActual(null, null, null),
    val rpe: Int? = null,
    val technique: Int? = null,
    val runStartedAt: Long? = null,
    /** Seconds of each easy run done this session. */
    val runs: List<Int> = emptyList(),
    val results: List<StationLog> = emptyList(),
    val skipped: Int = 0,
    val startedAt: Long = 0L,
    val endedAt: Long? = null,
    /** Bumped when a rest ends so the screen can flash. */
    val changes: Int = 0,
) {
    val block: StationBlock? get() = blocks.getOrNull(index)
    val totalRounds: Int get() = block?.prescription?.rounds ?: 1
}

class HyroxController(private val now: () -> Long = { SystemClock.elapsedRealtime() }) {
    private val _ui = MutableStateFlow(HyroxUi())
    val ui: StateFlow<HyroxUi> = _ui.asStateFlow()

    fun nowMs(): Long = now()

    // ------------------------------------------------------------------ setup

    /** Opens the setup. Ignored while a session is running or waiting to be saved. */
    fun prepare(input: HyroxPlanInput, minutes: Int, focus: Set<HyroxStation>, withRuns: Boolean = false) {
        if (_ui.value.phase != HyroxPhase.SETUP) return
        _ui.value = buildPlan(
            HyroxUi(minutes = snapMinutes(minutes), focus = focus, withRuns = withRuns && input.runsAllowed, entryFocus = focus),
            input,
        )
    }

    fun rebuild(input: HyroxPlanInput, minutes: Int? = null, focus: Set<HyroxStation>? = null, withRuns: Boolean? = null, shuffle: Boolean = false) {
        _ui.update { u ->
            if (u.phase != HyroxPhase.SETUP) return@update u
            var next = u.copy(
                minutes = minutes?.let(::snapMinutes) ?: u.minutes,
                focus = focus ?: u.focus,
                withRuns = (withRuns ?: u.withRuns) && input.runsAllowed,
                excluded = if (focus != null) emptySet() else u.excluded,
            )
            if (shuffle) {
                val base = next.focus.ifEmpty { HyroxStation.entries.toSet() }
                val used = u.plan?.blocks?.map { it.station }?.toSet().orEmpty()
                next = next.copy(excluded = if ((base - used).size >= 2) used else emptySet())
            }
            buildPlan(next, input)
        }
    }

    private fun buildPlan(u: HyroxUi, input: HyroxPlanInput): HyroxUi {
        val base = u.focus.ifEmpty { HyroxStation.entries.toSet() }
        val plan = HyroxSessionPlan.build(
            u.minutes, input.records, input.division, input.settings,
            focus = if (u.excluded.isEmpty()) u.focus else base - u.excluded, withRuns = u.withRuns,
        )
        return u.copy(plan = plan)
    }

    fun start() = _ui.update { u ->
        val p = u.plan ?: return@update u
        if (p.blocks.isEmpty() || u.phase != HyroxPhase.SETUP) return@update u
        u.copy(
            phase = HyroxPhase.RUNNING, blocks = p.blocks, index = 0, startedAt = now(), endedAt = null,
            results = emptyList(), runs = emptyList(), skipped = 0, changes = 0,
        ).freshBlock()
    }

    // ------------------------------------------------------------------ lap and rest

    /** Go: starts the lap stopwatch. */
    fun go() = _ui.update { u ->
        if (u.phase == HyroxPhase.RUNNING && u.step == HyroxStep.READY) u.copy(step = HyroxStep.LAP, lapStartedAt = now()) else u
    }

    /** Done: stops the lap. Goes to rest, or to the rating step after the last round. */
    fun lapDone() = _ui.update { u ->
        val b = u.block
        val t0 = u.lapStartedAt
        if (u.phase != HyroxPhase.RUNNING || u.step != HyroxStep.LAP || b == null || t0 == null) return@update u
        val laps = u.laps + secondsSince(t0)
        if (u.round + 1 < b.prescription.rounds) {
            val rest = b.prescription.restSec
            if (rest > 0) {
                u.copy(laps = laps, round = u.round + 1, step = HyroxStep.REST, lapStartedAt = null, restEndsAt = now() + rest * 1000L, restTotalSec = rest)
            } else {
                u.copy(laps = laps, round = u.round + 1, step = HyroxStep.READY, lapStartedAt = null)
            }
        } else {
            u.copy(laps = laps, step = HyroxStep.RATE, lapStartedAt = null, actual = defaultActual(b), rpe = null, technique = null)
        }
    }

    /** Ends the rest when its time is up. Returns true exactly when that happened (the screen then beeps). */
    fun tick(): Boolean {
        var ended = false
        _ui.update { u ->
            if (u.step == HyroxStep.REST && now() >= u.restEndsAt) {
                ended = true
                u.copy(step = HyroxStep.READY, changes = u.changes + 1)
            } else u
        }
        return ended
    }

    fun skipRest() = _ui.update { u -> if (u.step == HyroxStep.REST) u.copy(step = HyroxStep.READY) else u }

    // ------------------------------------------------------------------ rating

    fun adjustWeight(steps: Int) = _ui.update { u ->
        u.actual.weightKg?.let { w -> u.copy(actual = u.actual.copy(weightKg = (w + steps * WEIGHT_STEP).coerceAtLeast(0.0))) } ?: u
    }

    fun adjustDistance(steps: Int) = _ui.update { u ->
        val st = u.block?.station ?: return@update u
        u.actual.distanceM?.let { d -> u.copy(actual = u.actual.copy(distanceM = (d + steps * distanceStep(st)).coerceAtLeast(0))) } ?: u
    }

    fun adjustReps(steps: Int) = _ui.update { u ->
        u.actual.reps?.let { r -> u.copy(actual = u.actual.copy(reps = (r + steps * REPS_STEP).coerceAtLeast(0))) } ?: u
    }

    /** Tapping the chosen chip again clears it. */
    fun setRpe(v: Int) = _ui.update { it.copy(rpe = if (it.rpe == v) null else v) }
    fun setTechnique(v: Int) = _ui.update { it.copy(technique = if (it.technique == v) null else v) }

    /** Saves the block into the session with the corrected numbers and ratings. */
    fun saveBlock() = _ui.update { u ->
        if (u.phase != HyroxPhase.RUNNING || u.step != HyroxStep.RATE) return@update u
        val withLog = u.copy(results = u.results + logOf(u, u.actual, u.rpe, u.technique))
        if ((u.block?.runAfterM ?: 0) > 0) withLog.copy(step = HyroxStep.RUN, runStartedAt = null) else withLog.nextBlock()
    }

    // ------------------------------------------------------------------ easy run

    fun runGo() = _ui.update { u -> if (u.step == HyroxStep.RUN && u.runStartedAt == null) u.copy(runStartedAt = now()) else u }

    fun runDone() = _ui.update { u ->
        val t0 = u.runStartedAt
        if (u.step != HyroxStep.RUN || t0 == null) u else u.copy(runs = u.runs + secondsSince(t0)).nextBlock()
    }

    fun skipRun() = _ui.update { u -> if (u.step == HyroxStep.RUN) u.nextBlock() else u }

    // ------------------------------------------------------------------ leaving

    /** Skips the current station. If some laps were already done they are kept (as a partial block). */
    fun skipStation() = _ui.update { u ->
        if (u.phase != HyroxPhase.RUNNING || u.block == null) return@update u
        if (u.step == HyroxStep.RUN) return@update u.nextBlock()
        if (u.laps.isNotEmpty()) u.copy(results = u.results + partialLog(u)).nextBlock()
        else u.copy(skipped = u.skipped + 1).nextBlock()
    }

    /** Ends the session now and keeps what is done. A block in the rating step, or with laps done, is kept too. */
    fun endEarly() = _ui.update { u ->
        if (u.phase != HyroxPhase.RUNNING) return@update u
        val results = when {
            u.step == HyroxStep.RATE -> u.results + logOf(u, u.actual, u.rpe, u.technique)
            u.step != HyroxStep.RUN && u.laps.isNotEmpty() -> u.results + partialLog(u)
            else -> u.results
        }
        u.copy(results = results, phase = HyroxPhase.DONE, endedAt = now())
    }

    fun reset() { _ui.value = HyroxUi() }

    // ------------------------------------------------------------------ helpers

    fun elapsedMs(u: HyroxUi = _ui.value): Long = ((u.endedAt ?: now()) - u.startedAt).coerceAtLeast(0)

    private fun secondsSince(t0: Long): Int = ((now() - t0 + 500) / 1000).toInt().coerceAtLeast(0)

    private fun HyroxUi.freshBlock(): HyroxUi = copy(
        step = HyroxStep.READY, round = 0, laps = emptyList(), lapStartedAt = null, restEndsAt = 0L, restTotalSec = 0,
        actual = HyroxActual(null, null, null), rpe = null, technique = null, runStartedAt = null,
    )

    private fun HyroxUi.nextBlock(): HyroxUi =
        if (index + 1 >= blocks.size) copy(phase = HyroxPhase.DONE, endedAt = now(), step = HyroxStep.READY, laps = emptyList(), lapStartedAt = null)
        else copy(index = index + 1).freshBlock()

    private fun partialLog(u: HyroxUi): StationLog {
        val b = u.block!!
        val frac = u.laps.size.toDouble() / b.prescription.rounds
        val full = defaultActual(b)
        val log = logOf(u, HyroxActual(full.weightKg, full.distanceM?.let { (it * frac).toInt() }, full.reps?.let { (it * frac).toInt() }), null, null)
        // Ended early on purpose: judge it against what was done, so it is neutral (no step up, never 'hard').
        return log.copy(plannedWork = log.distanceM ?: log.reps)
    }

    private fun logOf(u: HyroxUi, a: HyroxActual, rpe: Int?, technique: Int?): StationLog {
        val b = u.block!!
        return StationLog(
            stationId = b.station.id, weightKg = a.weightKg, distanceM = a.distanceM, reps = a.reps,
            seconds = u.laps.sum().takeIf { it > 0 }, rpe = rpe, technique = technique, level = b.prescription.level,
            plannedWork = b.prescription.totalWork.takeIf { it > 0 },
        )
    }

    companion object {
        const val WEIGHT_STEP = 2.5
        const val REPS_STEP = 5
        val MINUTE_CHOICES = listOf(20, 30, 45, 60, 90)

        fun snapMinutes(m: Int): Int = MINUTE_CHOICES.minByOrNull { kotlin.math.abs(it - m) } ?: 45

        /** Metres per stepper tap: the machines move in big steps, the sleds and lunges in small ones. */
        fun distanceStep(s: HyroxStation): Int = when (s) {
            HyroxStation.SKIERG, HyroxStation.ROW -> 50
            HyroxStation.FARMERS_CARRY -> 10
            else -> 5
        }

        /** Prefill for the rating step: the prescription's totals over all its rounds. */
        fun defaultActual(b: StationBlock): HyroxActual {
            val p = b.prescription
            return HyroxActual(
                weightKg = p.weightKg.takeIf { b.station.weighted },
                distanceM = if (b.station.kind == StationKind.DISTANCE) p.totalWork else null,
                reps = if (b.station.kind == StationKind.REPS) p.totalWork else null,
            )
        }
    }
}

/** Plain-text helpers shared by the screens and the tests. */
object HyroxText {
    fun clock(sec: Int): String = "${sec / 60}:${"%02d".format(sec % 60)}"

    private fun kg(x: Double) = if (x % 1.0 == 0.0) x.toInt().toString() else x.toString()

    /** "4 x 12 m · 40 kg · rest 90 s". Weight is per hand for the farmers carry. */
    fun prescription(station: HyroxStation, p: StationPrescription): String {
        val per = if (station.kind == StationKind.REPS) "${p.reps} reps" else "${p.distanceM} m"
        val work = if (p.rounds > 1) "${p.rounds} x $per" else per
        return buildList {
            add(work)
            p.weightKg?.takeIf { station.weighted }?.let { add("${kg(it)} kg" + if (station == HyroxStation.FARMERS_CARRY) " per hand" else "") }
            if (p.rounds > 1 && p.restSec > 0) add("rest ${p.restSec} s")
        }.joinToString(" · ")
    }

    /** "Sled Push 50 m at 40 kg in 1:12". */
    fun summary(log: StationLog): String {
        val name = HyroxStation.byId(log.stationId)?.displayName ?: log.stationId
        val sb = StringBuilder(name)
        log.distanceM?.let { sb.append(" $it m") }
        log.reps?.let { sb.append(" $it reps") }
        log.weightKg?.let { sb.append(" at ${kg(it)} kg") }
        log.seconds?.let { sb.append(" in ${clock(it)}") }
        return sb.toString()
    }

    /** "Easy runs: 2 x 400 m (3:10, 3:20)" for the record note; null when there were none. */
    fun runNote(runs: List<Int>, metres: Int = 400): String? =
        if (runs.isEmpty()) null else "Easy runs: ${runs.size} x $metres m (${runs.joinToString(", ") { clock(it) }})"
}
