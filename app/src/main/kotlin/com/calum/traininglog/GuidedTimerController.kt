package com.calum.traininglog

import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.GuidedEvent
import com.calum.traininglog.core.GuidedPlan
import com.calum.traininglog.core.GuidedRun
import com.calum.traininglog.core.Modality
import com.calum.traininglog.core.PlannedSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class GuidedPhase { SETUP, RUNNING, DONE }

/**
 * Everything the guided timer screen shows. [distanceM], [rpe] and [feeling] are the rating card's values;
 * [savedId] is the id of the record already saved when the session finished (the card then only edits it).
 */
data class GuidedUi(
    val session: PlannedSession? = null,
    val modality: Modality = Modality.ROW,
    val plan: GuidedPlan? = null,
    val phase: GuidedPhase = GuidedPhase.SETUP,
    val run: GuidedRun? = null,
    /** Bumped on each step change so the screen can beep-and-flash. */
    val changes: Int = 0,
    val distanceM: Int? = null,
    val rpe: Int = 3,
    val feeling: Feeling = Feeling.GOOD,
    val savedId: String? = null,
)

/**
 * State holder for the guided timer; lives in [AppViewModel] so rotation does not lose it. Time only moves when
 * [tick] is called with a real elapsed delta (the screen feeds it from the monotonic clock; tests feed it fakes).
 */
class GuidedTimerController {
    private val _ui = MutableStateFlow(GuidedUi())
    val ui: StateFlow<GuidedUi> = _ui.asStateFlow()

    /** Opens the setup for [session] (a running session is left alone). */
    fun prepare(session: PlannedSession, modality: Modality? = null) {
        if (_ui.value.phase == GuidedPhase.RUNNING) return
        val m = modality ?: GuidedPlan.defaultModality(session)
        val plan = GuidedPlan.from(session, m)
        _ui.value = GuidedUi(session = session, modality = m, plan = plan, rpe = plan.defaultRpe)
    }

    fun setModality(m: Modality) = _ui.update { u ->
        val s = u.session ?: return@update u
        if (u.phase != GuidedPhase.SETUP) return@update u
        val plan = GuidedPlan.from(s, m)
        u.copy(modality = m, plan = plan, rpe = plan.defaultRpe, distanceM = null)
    }

    fun start() = _ui.update { u ->
        val plan = u.plan ?: return@update u
        u.copy(phase = GuidedPhase.RUNNING, run = GuidedRun(plan.steps), changes = 0, savedId = null)
    }

    /** Advances the clock; returns the cue to play, if any. */
    fun tick(deltaMs: Long): GuidedEvent? {
        var event: GuidedEvent? = null
        _ui.update { u ->
            val run = u.run ?: return@update u
            if (u.phase != GuidedPhase.RUNNING) return@update u
            val (next, e) = run.advance(deltaMs)
            event = e
            u.copy(run = next, phase = if (next.finished) GuidedPhase.DONE else u.phase, changes = u.changes + if (e == GuidedEvent.CHANGE) 1 else 0)
        }
        return event
    }

    fun togglePause() = _ui.update { u -> u.run?.let { r -> u.copy(run = if (r.paused) r.resume() else r.pause()) } ?: u }

    /** The app left the foreground: pause and remember why. */
    fun pauseAway() = _ui.update { u ->
        val r = u.run
        if (u.phase == GuidedPhase.RUNNING && r != null && !r.paused) u.copy(run = r.pause(away = true)) else u
    }

    fun skip(): GuidedEvent? {
        var event: GuidedEvent? = null
        _ui.update { u ->
            val run = u.run ?: return@update u
            if (u.phase != GuidedPhase.RUNNING) return@update u
            val (next, e) = run.skip()
            event = e
            u.copy(run = next, phase = if (next.finished) GuidedPhase.DONE else u.phase, changes = u.changes + 1)
        }
        return event
    }

    fun end() = _ui.update { if (it.phase == GuidedPhase.RUNNING) it.copy(phase = GuidedPhase.DONE) else it }

    // ---- rating card

    /** The first tap on "+" from blank starts at a plausible number for the time done; later taps step by [step]. */
    fun adjustDistance(delta: Int) = _ui.update { u ->
        val cur = u.distanceM
        val next = when {
            cur == null && delta > 0 -> suggestedDistance(u)
            cur == null -> null
            else -> (cur + delta).takeIf { it > 0 }
        }
        u.copy(distanceM = next)
    }

    fun setDistance(m: Int?) = _ui.update { it.copy(distanceM = m?.takeIf { v -> v > 0 }) }
    fun setRpe(v: Int) = _ui.update { it.copy(rpe = v.coerceIn(1, 10)) }
    fun setFeeling(f: Feeling) = _ui.update { it.copy(feeling = f) }
    fun markSaved(id: String) = _ui.update { it.copy(savedId = id) }
    fun reset() { _ui.value = GuidedUi() }

    /** Rough metres per minute at an easy pace; only a starting point for the stepper. */
    private fun suggestedDistance(u: GuidedUi): Int {
        val perMin = when (u.modality) { Modality.ROW -> 200; Modality.SKIERG -> 170; Modality.BIKE -> 450; else -> 100 }
        val minutes = ((u.run?.completedSec ?: u.plan?.totalSec ?: 0) / 60).coerceAtLeast(1)
        return ((minutes * perMin + 50) / 100 * 100).coerceAtLeast(100)
    }
}
