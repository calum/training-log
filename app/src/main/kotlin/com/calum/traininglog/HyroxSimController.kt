package com.calum.traininglog

import android.os.SystemClock
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.HyroxDivision
import com.calum.traininglog.core.HyroxRuns
import com.calum.traininglog.core.HyroxSim
import com.calum.traininglog.core.RunMode
import com.calum.traininglog.core.RunStage
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.core.SimFormat
import com.calum.traininglog.core.SimLeg
import com.calum.traininglog.core.SimLegKind
import com.calum.traininglog.core.SimMeta
import com.calum.traininglog.core.SimPlan
import com.calum.traininglog.core.SimPreset
import com.calum.traininglog.core.StationLog
import com.calum.traininglog.core.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/*
 * Hyrox SIMULATION: setup, player and results behind one route (see HyroxSimScreen). Same pattern as
 * HyroxController: all state in plain data inside the ViewModel, every time stamp on a monotonic clock
 * (default SystemClock.elapsedRealtime; tests inject a fake one), so nothing is lost to rotation or a glance at
 * another app.
 *
 * The race clock is continuous: tapping Done ends one leg and starts the next in the same instant (a real race has
 * no gap). A short 3-2-1 lead-in precedes leg 1. A treadmill run leg COUNTS DOWN the time for 1 km at the planned
 * pace and moves on by itself when it reaches zero (+10 s or Done early adjust it); every other leg is a stopwatch.
 * Pausing freezes both clocks. A skipped leg is recorded as skipped, with no penalty; ending early keeps what is done.
 */

enum class SimPhase { SETUP, RUNNING, DONE }

/** What the setup needs, read from the app data by the ViewModel. */
data class SimInput(
    val division: HyroxDivision,
    val settings: UserSettings,
    val stage: RunStage,
    val treadmillAtPlace: Boolean,
)

/** One leg that was done (with its seconds and effort) or skipped. */
data class SimLegResult(val index: Int, val seconds: Int, val rpe: Int?, val skipped: Boolean = false)

data class HyroxSimUi(
    val phase: SimPhase = SimPhase.SETUP,
    // ---- setup
    val format: SimFormat = SimFormat.FULL,
    val loadFactor: Double = 1.0,
    val runMode: RunMode = RunMode.ROW,
    /** True once the user picked a run mode themselves (the knee-gated default is then left alone). */
    val runModeChosen: Boolean = false,
    /** Optional finish-time target in seconds; null means effort guidance only. */
    val targetSec: Int? = null,
    val stage: RunStage = RunStage.NONE,
    val treadmillHere: Boolean = true,
    val division: HyroxDivision = HyroxDivision.MEN_OPEN,
    val overrides: Map<String, Double> = emptyMap(),
    val paceSecPerKm: Int = 400,
    val entryPreset: SimPreset? = null,
    val plan: SimPlan? = null,
    // ---- running
    val legIndex: Int = 0,
    /** When the race clock starts (3 s after Start: the 3-2-1 lead-in). */
    val startedAt: Long = 0L,
    val legStartedAt: Long = 0L,
    val pausedAt: Long? = null,
    /** "+10 s" added to the treadmill countdown of the current leg. */
    val extraSec: Int = 0,
    val results: List<SimLegResult> = emptyList(),
    /** The effort chip row belongs to this finished leg (null when none is showing). */
    val ratingIndex: Int? = null,
    val ratingAt: Long = 0L,
    /** Bumped on every leg change so the screen can flash and beep. */
    val changes: Int = 0,
    /** True when the last leg change was the treadmill countdown reaching zero (the screen plays the done beep). */
    val autoAdvanced: Boolean = false,
    val endedAt: Long? = null,
    val endedEarly: Boolean = false,
    /** The id of the record saved when the simulation ended (the results card then only edits it). */
    val savedId: String? = null,
    /** True when a past simulation is only being looked at. */
    val viewing: Boolean = false,
    val viewId: String? = null,
) {
    val leg: SimLeg? get() = if (phase == SimPhase.RUNNING) plan?.legs?.getOrNull(legIndex) else null
    val nextLeg: SimLeg? get() = plan?.legs?.getOrNull(legIndex + 1)

    /** Seconds of the treadmill countdown of the current leg, or null for a stopwatch leg. */
    val countdownSec: Int?
        get() {
            val l = leg ?: return null
            val t = l.targetSec
            return if (l.kind == SimLegKind.RUN && l.runMode == RunMode.TREADMILL && t != null) t + extraSec else null
        }
    /** Run and station legs done (transitions are not counted). */
    val doneCount: Int get() = results.count { !it.skipped && plan?.legs?.getOrNull(it.index)?.kind != SimLegKind.TRANSITION }

    /** The RACE-leg numbers (0-based, transitions not counted) that were skipped. */
    val skippedIndexes: List<Int> get() = results.filter { it.skipped }.mapNotNull { plan?.legs?.getOrNull(it.index)?.raceIndex?.takeIf { r -> r >= 0 } }

    /** True while the current leg is a transition (the Roxzone) between a run and a station. */
    val inTransition: Boolean get() = leg?.kind == SimLegKind.TRANSITION
}

class HyroxSimController(private val now: () -> Long = { SystemClock.elapsedRealtime() }) {
    private val _ui = MutableStateFlow(HyroxSimUi())
    val ui: StateFlow<HyroxSimUi> = _ui.asStateFlow()

    fun nowMs(): Long = now()

    // ------------------------------------------------------------------ setup

    /** Opens the setup for [preset] (null: a full simulation at race loads). Ignored while running. */
    fun prepare(input: SimInput, preset: SimPreset?) {
        if (_ui.value.phase == SimPhase.RUNNING) return
        val p = preset ?: SimPreset(SimFormat.FULL, 1.0)
        val mode = HyroxSim.defaultRunMode(input.stage, input.treadmillAtPlace)
        _ui.value = buildPlan(
            HyroxSimUi(
                format = p.format, loadFactor = p.loadFactor, runMode = mode, entryPreset = preset,
            ),
            input,
        )
    }

    /** Changes a setup choice and rebuilds the legs. [clearTarget] removes the target time. */
    fun rebuild(
        input: SimInput, format: SimFormat? = null, loadFactor: Double? = null, runMode: RunMode? = null,
        targetSec: Int? = null, clearTarget: Boolean = false,
    ) {
        _ui.update { u ->
            if (u.phase != SimPhase.SETUP) return@update u
            buildPlan(
                u.copy(
                    format = format ?: u.format,
                    loadFactor = loadFactor ?: u.loadFactor,
                    runMode = runMode ?: u.runMode,
                    runModeChosen = u.runModeChosen || runMode != null,
                    targetSec = if (clearTarget) null else targetSec?.coerceIn(MIN_TARGET_SEC, MAX_TARGET_SEC) ?: u.targetSec,
                ),
                input,
            )
        }
    }

    /** The target stepper: [deltaMin] minutes, starting from the typical estimate when no target is set yet. */
    fun stepTarget(input: SimInput, deltaMin: Int) {
        val u = _ui.value
        val base = u.targetSec ?: (((u.plan?.estimatedMinutes ?: 90) + 4) / 5 * 5) * 60
        rebuild(input, targetSec = base + deltaMin * 60)
    }

    private fun buildPlan(u: HyroxSimUi, input: SimInput): HyroxSimUi {
        // The knee-gated default follows the stage until the user chooses; a chosen mode is kept.
        val mode = if (u.runModeChosen) u.runMode else HyroxSim.defaultRunMode(input.stage, input.treadmillAtPlace)
        val pace = (3600.0 / input.settings.hyroxRunSpeedKmh).toInt()
        val plan = HyroxSim.build(u.format, input.division, input.settings, u.loadFactor, mode, pace, u.targetSec)
        return u.copy(
            plan = plan, runMode = mode, stage = input.stage, treadmillHere = input.treadmillAtPlace, division = input.division,
            overrides = input.settings.hyroxWeightOverrides, paceSecPerKm = pace,
        )
    }

    fun start() = _ui.update { u ->
        val p = u.plan ?: return@update u
        if (u.phase != SimPhase.SETUP || p.legs.isEmpty()) return@update u
        val t = now() + LEAD_IN_MS
        u.copy(
            phase = SimPhase.RUNNING, legIndex = 0, startedAt = t, legStartedAt = t, pausedAt = null, extraSec = 0,
            results = emptyList(), ratingIndex = null, changes = 0, autoAdvanced = false, endedAt = null, endedEarly = false,
            savedId = null, viewing = false, viewId = null,
        )
    }

    // ------------------------------------------------------------------ the race

    /** True while the 3-2-1 lead-in before leg 1 is still counting. */
    fun inLeadIn(u: HyroxSimUi = _ui.value): Boolean = leadInLeft(u) > 0

    /** Whole seconds left of the lead-in (3, 2, 1), or 0 when it is over. */
    fun leadInLeft(u: HyroxSimUi = _ui.value): Int = leadInLeftAt(u, now())

    /** Done: ends the current leg and starts the next one in the same instant (ends the simulation after the last). */
    fun legDone() = _ui.update { u ->
        if (u.phase != SimPhase.RUNNING || u.pausedAt != null || now() < u.legStartedAt) return@update u
        complete(u, secondsSince(u.legStartedAt), auto = false)
    }

    /** Skips the current leg: recorded as skipped (no time), with no penalty. On a transition it just ends the wait. */
    fun skipLeg() = _ui.update { u ->
        if (u.phase != SimPhase.RUNNING || u.pausedAt != null) return@update u
        if (u.inTransition) return@update if (now() < u.legStartedAt) u else complete(u, secondsSince(u.legStartedAt), auto = false)
        advance(u.copy(results = u.results + SimLegResult(u.legIndex, 0, null, skipped = true)), auto = false, rateIndex = null)
    }

    /** Treadmill countdown: moves on by itself when it reaches zero. Returns true exactly then (the screen beeps). */
    fun tick(): Boolean {
        var advanced = false
        _ui.update { u ->
            val target = u.countdownSec
            if (u.phase != SimPhase.RUNNING || u.pausedAt != null || target == null || now() < u.legStartedAt) return@update u
            if (now() - u.legStartedAt >= target * 1000L) {
                advanced = true
                complete(u, target, auto = true)
            } else u
        }
        return advanced
    }

    /** "+10 s": more time on this treadmill leg. */
    fun addTime(sec: Int = EXTRA_SEC) = _ui.update { u -> if (u.countdownSec != null && u.pausedAt == null) u.copy(extraSec = u.extraSec + sec) else u }

    fun pause() = _ui.update { u -> if (u.phase == SimPhase.RUNNING && u.pausedAt == null) u.copy(pausedAt = now()) else u }

    /** Resumes: both clocks carry on from where they stopped (the paused time is cut out). */
    fun resume() = _ui.update { u ->
        val p = u.pausedAt ?: return@update u
        val d = (now() - p).coerceAtLeast(0)
        u.copy(pausedAt = null, startedAt = u.startedAt + d, legStartedAt = u.legStartedAt + d, ratingAt = u.ratingAt + d)
    }

    /** The one-tap effort for a finished leg (it already holds the previous value by default). */
    fun rate(index: Int, rpe: Int) = _ui.update { u ->
        u.copy(results = u.results.map { if (it.index == index && !it.skipped) it.copy(rpe = rpe) else it })
    }

    fun dismissRating() = _ui.update { it.copy(ratingIndex = null) }

    /** Ends the simulation now and keeps every leg done so far (the leg in progress is not recorded). */
    fun endEarly() = _ui.update { u ->
        if (u.phase != SimPhase.RUNNING) return@update u
        val t = u.pausedAt ?: now()
        u.copy(phase = SimPhase.DONE, endedAt = t, endedEarly = true, ratingIndex = null, pausedAt = null, autoAdvanced = false)
    }

    fun markSaved(id: String) = _ui.update { it.copy(savedId = id) }

    /** Shows a past simulation's results (nothing runs). */
    fun view(recordId: String) { _ui.value = HyroxSimUi(phase = SimPhase.DONE, viewing = true, viewId = recordId, savedId = recordId) }

    fun reset() { _ui.value = HyroxSimUi() }

    // ------------------------------------------------------------------ helpers

    /** The race clock in seconds: continuous from the end of the lead-in, frozen while paused or finished. */
    fun elapsedSec(u: HyroxSimUi = _ui.value): Int = clockSec(u, now())

    /** Seconds into the current leg (0 during the lead-in; frozen while paused). */
    fun legSec(u: HyroxSimUi = _ui.value): Int = legSecAt(u, now())

    private fun secondsSince(t0: Long): Int = ((now() - t0 + 500) / 1000).toInt().coerceAtLeast(0)

    private fun complete(u: HyroxSimUi, sec: Int, auto: Boolean): HyroxSimUi {
        if (u.inTransition) {
            // "Ready, go": the transition's time is kept (at least 1 s, so 0 can mean 'never done') and the next leg starts now.
            // The effort row of the leg before stays up for its window.
            val done = u.copy(results = u.results + SimLegResult(u.legIndex, sec.coerceAtLeast(1), null))
            return advance(done, auto, rateIndex = u.ratingIndex, keepRatingAt = true)
        }
        val prev = u.results.lastOrNull { !it.skipped && it.rpe != null }?.rpe ?: DEFAULT_RPE
        val done = u.copy(results = u.results + SimLegResult(u.legIndex, sec, prev))
        return advance(done, auto, rateIndex = u.legIndex)
    }

    private fun advance(u: HyroxSimUi, auto: Boolean, rateIndex: Int?, keepRatingAt: Boolean = false): HyroxSimUi {
        val plan = u.plan ?: return u
        val t = now()
        return if (u.legIndex + 1 >= plan.legs.size) {
            u.copy(phase = SimPhase.DONE, endedAt = t, endedEarly = false, ratingIndex = null, autoAdvanced = auto, changes = u.changes + 1)
        } else {
            u.copy(
                legIndex = u.legIndex + 1, legStartedAt = t, extraSec = 0, changes = u.changes + 1, autoAdvanced = auto,
                ratingIndex = rateIndex, ratingAt = if (keepRatingAt) u.ratingAt else t,
            )
        }
    }

    // ------------------------------------------------------------------ the record

    companion object {
        const val LEAD_IN_MS = 3000L
        const val EXTRA_SEC = 10
        const val DEFAULT_RPE = 7
        const val MIN_TARGET_SEC = 20 * 60
        const val MAX_TARGET_SEC = 3 * 3600

        /** How long the effort chips stay in view after a leg (ms). */
        const val RATING_WINDOW_MS = 12_000L

        /** The race clock at [nowMs]: from the end of the lead-in, frozen while paused or once finished. */
        fun clockSec(u: HyroxSimUi, nowMs: Long): Int {
            if (u.phase == SimPhase.SETUP) return 0
            val end = u.endedAt ?: u.pausedAt ?: nowMs
            return ((end - u.startedAt).coerceAtLeast(0) / 1000).toInt()
        }

        /** Seconds into the current leg at [nowMs] (0 during the lead-in; frozen while paused). */
        fun legSecAt(u: HyroxSimUi, nowMs: Long): Int {
            if (u.phase != SimPhase.RUNNING) return 0
            val end = u.pausedAt ?: nowMs
            return ((end - u.legStartedAt).coerceAtLeast(0) / 1000).toInt()
        }

        /** Whole seconds left of the 3-2-1 lead-in at [nowMs] (3, 2, 1), or 0 when it is over. */
        fun leadInLeftAt(u: HyroxSimUi, nowMs: Long): Int {
            if (u.phase != SimPhase.RUNNING || !(u.results.isEmpty() && u.legIndex == 0)) return 0
            val ref = u.pausedAt ?: nowMs
            return if (ref < u.legStartedAt) ((u.legStartedAt - ref + 999) / 1000).toInt() else 0
        }

        /** "6:12" under an hour, "1:12:30" from an hour. */
        fun longClock(sec: Int): String =
            if (sec >= 3600) "${sec / 3600}:${"%02d".format(sec % 3600 / 60)}:${"%02d".format(sec % 60)}" else "${sec / 60}:${"%02d".format(sec % 60)}"

        /** The saved legs in race order: run legs as stationId "run", station legs with load, distance or reps. */
        fun logs(u: HyroxSimUi): List<StationLog> {
            val plan = u.plan ?: return emptyList()
            return u.results.filter { !it.skipped }.sortedBy { it.index }.mapNotNull { r ->
                val leg = plan.legs.getOrNull(r.index) ?: return@mapNotNull null
                if (leg.kind == SimLegKind.TRANSITION) return@mapNotNull null
                if (leg.kind == SimLegKind.RUN) {
                    StationLog(HyroxRuns.ID, distanceM = leg.distanceM, seconds = r.seconds.takeIf { it > 0 }, rpe = r.rpe)
                } else {
                    val st = leg.station!!
                    StationLog(
                        stationId = st.id, weightKg = leg.weightKg, distanceM = leg.distanceM, reps = leg.reps,
                        seconds = r.seconds.takeIf { it > 0 }, rpe = r.rpe,
                        level = HyroxSim.levelFor(st, u.division, u.overrides, leg.weightKg),
                        // Judged against what was actually done at that load, so a lighter first attempt is neutral.
                        plannedWork = leg.distanceM ?: leg.reps,
                    )
                }
            }
        }

        /** The record to save for a finished (or ended early) simulation, or null when no leg was done. */
        fun record(u: HyroxSimUi, id: String, date: java.time.LocalDate, totalSec: Int, feeling: Feeling = Feeling.GOOD, kneePain: Int? = null): SessionRecord? {
            val plan = u.plan ?: return null
            val logs = logs(u)
            if (logs.isEmpty()) return null
            val total = totalSec.coerceAtLeast(1)
            val skipped = u.skippedIndexes
            // Transition k is the one after race leg k, which sits at index 2k + 1 of the full list.
            val trans = (0 until plan.format.legCount - 1).map { k -> u.results.firstOrNull { it.index == 2 * k + 1 && !it.skipped }?.seconds ?: 0 }
            return SessionRecord(
                id = id, date = date, type = SessionType.HYROX,
                minutesAvailable = plan.estimatedMinutes, minutesActual = ((total + 30) / 60).coerceAtLeast(1),
                feeling = feeling, kneePain = kneePain, dayLabel = plan.format.dayLabel, stations = logs,
                sim = SimMeta(
                    plan.format, plan.loadFactor, plan.runMode, total, plan.targetTotalSec, skipped, endedEarly = u.endedEarly,
                    transitionSec = trans, allowanceSec = plan.transitionSec,
                ),
            )
        }
    }
}
