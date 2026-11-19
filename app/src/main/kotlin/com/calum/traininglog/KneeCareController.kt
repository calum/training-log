package com.calum.traininglog

import com.calum.traininglog.core.CalfMode
import com.calum.traininglog.core.CoreEvent
import com.calum.traininglog.core.CoreKind
import com.calum.traininglog.core.CoreRun
import com.calum.traininglog.core.ExerciseLibrary
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.KneeCareRoutine
import com.calum.traininglog.core.KneeCareSession
import com.calum.traininglog.core.LoggedSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class KneeCarePhase { SETUP, RUNNING, DONE }

/** The load, reps done and optional reps-in-reserve for one working calf set (kg null = blank, to be typed). */
data class CalfEntry(val kg: Double?, val reps: Int, val rir: Int? = null)

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

    fun configure(minutes: Int? = null, mode: CalfMode? = null, knee: Int? = null, clearKnee: Boolean = false, calfOnly: Boolean? = null, reshuffle: Boolean = false) =
        _ui.update { u ->
            if (u.phase != KneeCarePhase.SETUP) return@update u
            u.copy(
                minutes = minutes ?: u.minutes, mode = mode ?: u.mode, knee = if (clearKnee) null else knee ?: u.knee,
                calfOnly = calfOnly ?: u.calfOnly, seed = if (reshuffle) u.seed + 1 else u.seed,
            )
        }

    fun setSession(s: KneeCareSession) = _ui.update { if (it.phase == KneeCarePhase.SETUP) it.copy(session = s) else it }

    fun start() = _ui.update { u ->
        val s = u.session ?: return@update u
        val entries = List(s.calfSets) { CalfEntry(s.calfStartKg, s.calfReps) }
        u.copy(phase = KneeCarePhase.RUNNING, run = CoreRun(s.steps), changes = 0, calf = entries, savedId = null)
    }

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
            LoggedSet(e.kg ?: 0.0, e.reps, s.calfReps, e.rir)
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
