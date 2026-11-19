package com.calum.traininglog

import android.os.SystemClock
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.HikeSuggestion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class HikePhase { SETUP, RUNNING, DONE }

/** A beep to play: halfway to the target, and the target itself. */
enum class HikeCue { HALFWAY, FINISH }

/**
 * Everything the hike / walk screens show. The clock is made of timestamps ([startedAt], [pausedAt], [pausedTotalMs]) rather
 * than ticks, so it keeps counting when the screen locks or the app is in the background. [savedId] is the record that was
 * saved the moment Finish was pressed; the finish card then only edits it.
 */
data class HikeUi(
    val phase: HikePhase = HikePhase.SETUP,
    /** Optional target length in minutes (null: no target, a plain stopwatch). */
    val targetMin: Int? = null,
    val suggestion: HikeSuggestion? = null,
    val startedAt: Long = 0L,
    val pausedAt: Long? = null,
    val pausedTotalMs: Long = 0L,
    /** The frozen time once finished. */
    val endedMs: Long = 0L,
    val halfBeeped: Boolean = false,
    val finishBeeped: Boolean = false,
    val distanceKm: Double? = null,
    val climbM: Int? = null,
    val feeling: Feeling = Feeling.GOOD,
    val note: String = "",
    val savedId: String? = null,
) {
    val paused: Boolean get() = pausedAt != null
}

/** State holder for the guided hike / walk stopwatch; lives in [AppViewModel] so rotation and screen-off lose nothing. */
class HikeController(private val now: () -> Long = { SystemClock.elapsedRealtime() }) {
    private val _ui = MutableStateFlow(HikeUi())
    val ui: StateFlow<HikeUi> = _ui.asStateFlow()

    /** Opens the setup. A hike that is running or waiting to be saved is left alone. */
    fun prepare(suggestion: HikeSuggestion?, feeling: Feeling) {
        if (_ui.value.phase != HikePhase.SETUP) return
        _ui.value = HikeUi(suggestion = suggestion, feeling = feeling)
    }

    fun setTarget(minutes: Int?) = _ui.update { if (it.phase == HikePhase.SETUP) it.copy(targetMin = minutes) else it }

    fun start() = _ui.update { u ->
        if (u.phase != HikePhase.SETUP) u else u.copy(phase = HikePhase.RUNNING, startedAt = now(), pausedAt = null, pausedTotalMs = 0L, halfBeeped = false, finishBeeped = false)
    }

    /** Elapsed moving time in ms: frozen while paused and once finished. */
    fun elapsedMs(u: HikeUi = _ui.value): Long = when (u.phase) {
        HikePhase.SETUP -> 0L
        HikePhase.DONE -> u.endedMs
        HikePhase.RUNNING -> ((u.pausedAt ?: now()) - u.startedAt - u.pausedTotalMs).coerceAtLeast(0L)
    }

    fun togglePause() = _ui.update { u ->
        if (u.phase != HikePhase.RUNNING) u
        else if (u.pausedAt != null) u.copy(pausedTotalMs = u.pausedTotalMs + (now() - u.pausedAt), pausedAt = null)
        else u.copy(pausedAt = now())
    }

    /** Returns the cue due now, once each: halfway and the target. Both are skipped for a hike with no target (or under 10 min). */
    fun tick(): HikeCue? {
        var cue: HikeCue? = null
        _ui.update { u ->
            val target = u.targetMin
            if (u.phase != HikePhase.RUNNING || u.paused || target == null || target < MIN_BEEP_TARGET_MIN) return@update u
            val ms = elapsedMs(u)
            val targetMs = target * 60_000L
            when {
                !u.finishBeeped && ms >= targetMs -> { cue = HikeCue.FINISH; u.copy(finishBeeped = true, halfBeeped = true) }
                !u.halfBeeped && ms >= targetMs / 2 -> { cue = HikeCue.HALFWAY; u.copy(halfBeeped = true) }
                else -> u
            }
        }
        return cue
    }

    /** The big Finish button: freezes the clock and opens the finish card (the ViewModel saves right away). */
    fun finish() = _ui.update { u ->
        if (u.phase != HikePhase.RUNNING) u else u.copy(phase = HikePhase.DONE, endedMs = elapsedMs(u), pausedAt = null)
    }

    fun adjustDistance(deltaKm: Double) = _ui.update { u ->
        u.copy(distanceKm = (((u.distanceKm ?: 0.0) + deltaKm).coerceIn(0.0, MAX_KM) * 10).let { Math.round(it) / 10.0 }.takeIf { it > 0.0 })
    }

    fun adjustClimb(deltaM: Int) = _ui.update { u -> u.copy(climbM = ((u.climbM ?: 0) + deltaM).coerceIn(0, MAX_CLIMB_M).takeIf { it > 0 }) }

    fun setFeeling(f: Feeling) = _ui.update { it.copy(feeling = f) }
    fun setNote(n: String) = _ui.update { it.copy(note = n) }
    fun markSaved(id: String) = _ui.update { it.copy(savedId = id) }
    fun reset() { _ui.value = HikeUi() }

    companion object {
        const val MIN_BEEP_TARGET_MIN = 10
        const val MAX_KM = 200.0
        const val MAX_CLIMB_M = 9000
        const val KM_STEP = 0.5
        const val CLIMB_STEP = 50
    }
}
