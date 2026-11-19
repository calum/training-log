package com.calum.traininglog

import com.calum.traininglog.core.CalfLadder
import com.calum.traininglog.core.CalfMode
import com.calum.traininglog.core.Effort
import com.calum.traininglog.core.CoreEvent
import com.calum.traininglog.core.CoreKind
import com.calum.traininglog.core.CoreRun
import com.calum.traininglog.core.ExerciseLibrary
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.KneeCareRoutine
import com.calum.traininglog.core.KneeCareSession
import com.calum.traininglog.core.LoggedSet
import com.calum.traininglog.core.rir
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** SETUP card, then the GET_READY interstitial (grab the gear, press Ready), then RUNNING, then DONE. */
enum class KneeCarePhase { SETUP, GET_READY, RUNNING, DONE }

/**
 * One working calf set: the load, reps done, the reps in reserve (from the feel tap), the ladder [level] and the reps aimed
 * for ([targetReps], 0 = use the session's) and the feel tap itself. A too easy tap upgrades the sets still to come.
 */
data class CalfEntry(
    val kg: Double?, val reps: Int, val rir: Int? = null,
    val level: Int = 1, val targetReps: Int = 0, val effort: Effort? = null,
)

/**
 * Everything the knee care screen shows. [calf] has one entry per working calf set; [savedId] is the id of the record that
 * was saved the moment the session ended (the finish card then only edits it).
 */
data class KneeCareUi(
    val phase: KneeCarePhase = KneeCarePhase.SETUP,
    val minutes: Int = 15,
    val calfOnly: Boolean = false,
    val mode: CalfMode = CalfMode.HEAVY,
    /** Knee score 0-10 before starting (pre-filled from the last one); it shapes the routine. */
    val knee: Int? = null,
    val seed: Long = 0,
    val session: KneeCareSession? = null,
    val run: CoreRun? = null,
    /** Beep-and-flash counter: bumped on each step change so the screen can flash. */
    val changes: Int = 0,
    val calf: List<CalfEntry> = emptyList(),
    val feeling: Feeling = Feeling.GOOD,
    /** Optional knee score after the session (feeds the knee-gated running stage like other sessions). */
    val kneeAfter: Int? = null,
    val savedId: String? = null,
    /** Level changed by hand on the setup card (-1 easier, +1 harder). */
    val levelAdjust: Int = 0,
    /** Gear ticked on the checklist: memory only, never required. */
    val checked: Set<String> = emptySet(),
)

/**
 * State holder for the guided knee care session; lives in [AppViewModel] so rotation does not lose it. It runs the same
 * [CoreRun] as the mat session, except that rep-based steps (the calf sets) wait for the user to press "Done, next"
 * instead of counting down. Time only moves when [tick] is called with a real elapsed delta.
 */
class KneeCareController {
    private val _ui = MutableStateFlow(KneeCareUi())
    val ui: StateFlow<KneeCareUi> = _ui.asStateFlow()

    /** Opens the setup (a session already running is left alone). */
    fun prepare(minutes: Int, mode: CalfMode, knee: Int?, calfOnly: Boolean, session: KneeCareSession) {
        if (_ui.value.phase == KneeCarePhase.RUNNING) return
        _ui.value = KneeCareUi(minutes = minutes, calfOnly = calfOnly, mode = mode, knee = knee, session = session)
    }

    fun configure(minutes: Int? = null, mode: CalfMode? = null, knee: Int? = null, clearKnee: Boolean = false, calfOnly: Boolean? = null, reshuffle: Boolean = false, levelStep: Int = 0) =
        _ui.update { u ->
            if (u.phase != KneeCarePhase.SETUP) return@update u
            u.copy(
                minutes = minutes ?: u.minutes, mode = mode ?: u.mode, knee = if (clearKnee) null else knee ?: u.knee,
                calfOnly = calfOnly ?: u.calfOnly, seed = if (reshuffle) u.seed + 1 else u.seed,
                levelAdjust = u.levelAdjust + levelStep,
            )
        }

    fun setSession(s: KneeCareSession) = _ui.update { if (it.phase == KneeCarePhase.SETUP) it.copy(session = s) else it }

    /** Ticking gear on the checklist (optional memory only). */
    fun toggleGear(name: String) = _ui.update { u -> u.copy(checked = if (name in u.checked) u.checked - name else u.checked + name) }

    /** "Start": opens the Get ready step. Nothing runs and no time passes until [ready]. */
    fun start() = _ui.update { u ->
        val s = u.session ?: return@update u
        if (u.phase != KneeCarePhase.SETUP) return@update u
        val entries = List(s.calfSets) { CalfEntry(s.calfStartKg, s.calfReps, level = s.calfLevel, targetReps = s.calfReps) }
        u.copy(phase = KneeCarePhase.GET_READY, run = CoreRun(s.steps), changes = 0, calf = entries, savedId = null)
    }

    /** "Ready": the warm-up starts now. */
    fun ready() = _ui.update { if (it.phase == KneeCarePhase.GET_READY) it.copy(phase = KneeCarePhase.RUNNING) else it }

    /** Back from the Get ready step to the setup card. */
    fun backToSetup() = _ui.update { if (it.phase == KneeCarePhase.GET_READY) it.copy(phase = KneeCarePhase.SETUP, run = null, calf = emptyList()) else it }

    /** Advances the clock; returns the cue to play, if any. A rep-based step only lets time pass (it ends on [next]). */
    fun tick(deltaMs: Long): CoreEvent? {
        var event: CoreEvent? = null
        _ui.update { u ->
            val run = u.run ?: return@update u
            val s = u.session ?: return@update u
            if (u.phase != KneeCarePhase.RUNNING || run.paused || run.finished || deltaMs <= 0) return@update u
            if (s.isRepStep(run.index)) {
                return@update u.copy(run = run.copy(elapsedMs = run.elapsedMs + deltaMs, totalElapsedMs = run.totalElapsedMs + deltaMs))
            }
            val (next, e) = run.advance(deltaMs)
            event = e
            u.copy(run = next, phase = if (next.finished) KneeCarePhase.DONE else u.phase, changes = u.changes + if (e == CoreEvent.CHANGE) 1 else 0)
        }
        return event
    }

    /** "Done, next": finishes the current (rep-based) step and moves on. */
    fun next(): CoreEvent? {
        var event: CoreEvent? = null
        _ui.update { u ->
            val run = u.run ?: return@update u
            if (u.phase != KneeCarePhase.RUNNING || run.finished) return@update u
            val after = run.copy(index = run.index + 1, elapsedMs = 0, paused = false, pausedAway = false)
            event = if (after.finished) CoreEvent.DONE else CoreEvent.CHANGE
            u.copy(run = after, phase = if (after.finished) KneeCarePhase.DONE else u.phase, changes = u.changes + 1)
        }
        return event
    }

    fun togglePause() = _ui.update { u -> u.run?.let { r -> u.copy(run = if (r.paused) r.resume() else r.pause()) } ?: u }

    /** The app left the foreground: pause and remember why. */
    fun pauseAway() = _ui.update { u ->
        val r = u.run
        if (u.phase == KneeCarePhase.RUNNING && r != null && !r.paused) u.copy(run = r.pause(away = true)) else u
    }

    /** Skips the current step (a calf set skipped is simply not logged). */
    fun skip(): CoreEvent? {
        var event: CoreEvent? = null
        _ui.update { u ->
            val run = u.run ?: return@update u
            if (u.phase != KneeCarePhase.RUNNING) return@update u
            val (next, e) = run.skip()
            event = e
            u.copy(run = next, phase = if (next.finished) KneeCarePhase.DONE else u.phase, changes = u.changes + 1)
        }
        return event
    }

    fun end() = _ui.update { if (it.phase == KneeCarePhase.RUNNING) it.copy(phase = KneeCarePhase.DONE) else it }

    // ---- calf sets

    fun setCalf(index: Int, f: (CalfEntry) -> CalfEntry) = _ui.update { u ->
        if (index !in u.calf.indices) u else u.copy(calf = u.calf.mapIndexed { i, e -> if (i == index) f(e) else e })
    }

    /** Changing the load of a set also carries it to the sets still to come (the usual case: the same weight every set). */
    fun setCalfWeight(index: Int, kg: Double) = _ui.update { u ->
        if (index !in u.calf.indices) u else u.copy(calf = u.calf.mapIndexed { i, e -> if (i >= index) e.copy(kg = kg) else e })
    }

    /**
     * The one-tap effort row after a calf set (null clears it). It never blocks. It stores an RIR-equivalent, upgrades (too
     * easy) or keeps (just right, too hard) the sets still to come, and shortens or lengthens the rests still to come: after
     * a too easy set the rest is 30 s, after a too hard one a little longer. A rest in progress is changed too (never to less
     * than it has already run). Skipped rests change nothing else.
     */
    fun rate(index: Int, effort: Effort?) = _ui.update { u ->
        val s = u.session ?: return@update u
        val run = u.run ?: return@update u
        if (index !in u.calf.indices) return@update u
        val entries = u.calf.toMutableList()
        entries[index] = entries[index].copy(effort = effort, rir = effort?.rir)
        val e = entries[index]
        val (level, target) = CalfLadder.follower(e.level, e.targetReps, effort, entries.getOrNull(index - 1)?.effort, s.kit, s.calfMode)
        for (j in index + 1 until entries.size) {
            val old = entries[j]
            val spec = CalfLadder.spec(level, s.kit)
            entries[j] = old.copy(
                level = level, targetReps = target, reps = target,
                kg = if (level != old.level) CalfLadder.loadKg(spec, s.kit) else old.kg, effort = null, rir = null,
            )
        }
        val steps = run.steps.mapIndexed { i, st ->
            val set = if (st.kind == CoreKind.REST && st.exerciseId == ExerciseLibrary.CALF_HOME_ID && i >= run.index) s.calfSetAt(i) else null
            if (set == null) st else {
                val eff = entries.take(set + 1).lastOrNull { it.effort != null }?.effort
                var sec = CalfLadder.restSec(s.steps[i].seconds, eff)
                if (i == run.index) sec = maxOf(sec, (run.elapsedMs / 1000).toInt() + 1)
                st.copy(seconds = sec)
            }
        }
        u.copy(calf = entries, run = run.copy(steps = steps))
    }

    // ---- finish card

    fun setFeeling(f: Feeling) = _ui.update { it.copy(feeling = f) }
    fun setKneeAfter(v: Int?) = _ui.update { it.copy(kneeAfter = v) }
    fun markSaved(id: String) = _ui.update { it.copy(savedId = id) }
    fun reset() { _ui.value = KneeCareUi() }

    // ---- what was done

    /** The working calf sets that were completed (not skipped, not still in progress), as logged sets. */
    fun doneCalfSets(u: KneeCareUi): List<LoggedSet> {
        val run = u.run ?: return emptyList()
        val s = u.session ?: return emptyList()
        return s.steps.indices.filter { i ->
            s.steps[i].kind == CoreKind.WORK && s.steps[i].exerciseId == ExerciseLibrary.CALF_HOME_ID && i < run.index && i !in run.skipped
        }.mapNotNull { i ->
            val e = u.calf.getOrNull(s.calfSetAt(i) ?: return@mapNotNull null) ?: return@mapNotNull null
            LoggedSet(e.kg ?: 0.0, e.reps, if (e.targetReps > 0) e.targetReps else s.calfReps, e.rir, e.level)
        }
    }

    /** Names of the exercises done (a timed one counts after 10 s, a skipped one never), no repeats. */
    fun doneNames(u: KneeCareUi): List<String> {
        val run = u.run ?: return emptyList()
        val s = u.session ?: return emptyList()
        return s.steps.indices.filter { i ->
            val st = s.steps[i]
            st.kind != CoreKind.REST && i !in run.skipped && (i < run.index || (i == run.index && !KneeCareRoutine.isRepStep(st) && run.elapsedMs >= 10_000))
        }.map { s.steps[it].name }.distinct()
    }
}
